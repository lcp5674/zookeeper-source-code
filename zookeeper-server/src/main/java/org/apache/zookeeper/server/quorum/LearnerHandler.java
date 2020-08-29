/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;

import javax.security.sasl.SaslException;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.TxnLogProposalIterator;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.ZooTrace;
import org.apache.zookeeper.server.quorum.Leader.Proposal;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * There will be an instance of this class created by the Leader for each
 * learner. All communication with a learner is handled by this
 * class.
 */
public class LearnerHandler extends ZooKeeperThread {
    private static final Logger LOG = LoggerFactory.getLogger(LearnerHandler.class);

    protected final Socket sock;

    public Socket getSocket() {
        return sock;
    }

    final Leader leader;

    /**
     * Deadline for receiving the next ack. If we are bootstrapping then
     * it's based on the initLimit, if we are done bootstrapping it's based
     * on the syncLimit. Once the deadline is past this learner should
     * be considered no longer "sync'd" with the leader.
     */
    volatile long tickOfNextAckDeadline;

    /**
     * ZooKeeper server identifier of this learner
     */
    protected long sid = 0;

    long getSid() {
        return sid;
    }

    protected int version = 0x1;

    int getVersion() {
        return version;
    }

    /**
     * The packets to be sent to the learner
     */
    final LinkedBlockingQueue<QuorumPacket> queuedPackets =
            new LinkedBlockingQueue<QuorumPacket>();

    /**
     * This class controls the time that the Leader has been
     * waiting for acknowledgement of a proposal from this Learner.
     * If the time is above syncLimit, the connection will be closed.
     * It keeps track of only one proposal at a time, when the ACK for
     * that proposal arrives, it switches to the last proposal received
     * or clears the value if there is no pending proposal.
     */
    private class SyncLimitCheck {
        private boolean started = false;
        private long currentZxid = 0;
        private long currentTime = 0;
        private long nextZxid = 0;
        private long nextTime = 0;

        public synchronized void start() {
            started = true;
        }

        public synchronized void updateProposal(long zxid, long time) {
            if (!started) {
                return;
            }
            if (currentTime == 0) {
                currentTime = time;
                currentZxid = zxid;
            } else {
                nextTime = time;
                nextZxid = zxid;
            }
        }

        public synchronized void updateAck(long zxid) {
            if (currentZxid == zxid) {
                currentTime = nextTime;
                currentZxid = nextZxid;
                nextTime = 0;
                nextZxid = 0;
            } else if (nextZxid == zxid) {
                LOG.warn("ACK for " + zxid + " received before ACK for " + currentZxid + "!!!!");
                nextTime = 0;
                nextZxid = 0;
            }
        }

        public synchronized boolean check(long time) {
            if (currentTime == 0) {
                return true;
            } else {
                long msDelay = (time - currentTime) / 1000000;
                return (msDelay < (leader.self.tickTime * leader.self.syncLimit));
            }
        }
    }

    ;

    private SyncLimitCheck syncLimitCheck = new SyncLimitCheck();

    private BinaryInputArchive ia;

    private BinaryOutputArchive oa;

    private final BufferedInputStream bufferedInput;
    private BufferedOutputStream bufferedOutput;

    /**
     * Keep track of whether we have started send packets thread
     */
    private volatile boolean sendingThreadStarted = false;

    /**
     * For testing purpose, force leader to use snapshot to sync with followers
     */
    public static final String FORCE_SNAP_SYNC = "zookeeper.forceSnapshotSync";
    private boolean forceSnapSync = false;

    /**
     * Keep track of whether we need to queue TRUNC or DIFF into packet queue
     * that we are going to blast it to the learner
     */
    private boolean needOpPacket = true;

    /**
     * Last zxid sent to the learner as part of synchronization
     */
    private long leaderLastZxid;

    LearnerHandler(Socket sock, BufferedInputStream bufferedInput, Leader leader) throws IOException {
        super("LearnerHandler-" + sock.getRemoteSocketAddress());
        this.sock = sock;
        this.leader = leader;
        this.bufferedInput = bufferedInput;

        if (Boolean.getBoolean(FORCE_SNAP_SYNC)) {
            forceSnapSync = true;
            LOG.info("Forcing snapshot sync is enabled");
        }

        try {
            if (leader.self != null) {
                leader.self.authServer.authenticate(sock,
                        new DataInputStream(bufferedInput));
            }
        } catch (IOException e) {
            LOG.error("Server failed to authenticate quorum learner, addr: {}, closing connection",
                    sock.getRemoteSocketAddress(), e);
            try {
                sock.close();
            } catch (IOException ie) {
                LOG.error("Exception while closing socket", ie);
            }
            throw new SaslException("Authentication failure: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("LearnerHandler ").append(sock);
        sb.append(" tickOfNextAckDeadline:").append(tickOfNextAckDeadline());
        sb.append(" synced?:").append(synced());
        sb.append(" queuedPacketLength:").append(queuedPackets.size());
        return sb.toString();
    }

    /**
     * If this packet is queued, the sender thread will exit
     */
    final QuorumPacket proposalOfDeath = new QuorumPacket();

    private LearnerType learnerType = LearnerType.PARTICIPANT;

    public LearnerType getLearnerType() {
        return learnerType;
    }

    /**
     * This method will use the thread to send packets added to the
     * queuedPackets list
     *
     * @throws InterruptedException
     */
    private void sendPackets() throws InterruptedException {
        long traceMask = ZooTrace.SERVER_PACKET_TRACE_MASK;
        while (true) {
            try {
                QuorumPacket p;
                p = queuedPackets.poll();
                if (p == null) {
                    bufferedOutput.flush();
                    p = queuedPackets.take();
                }

                if (p == proposalOfDeath) {
                    // Packet of death!
                    break;
                }
                if (p.getType() == Leader.PING) {
                    traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
                }
                if (p.getType() == Leader.PROPOSAL) {
                    syncLimitCheck.updateProposal(p.getZxid(), System.nanoTime());
                }
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logQuorumPacket(LOG, traceMask, 'o', p);
                }
                oa.writeRecord(p, "packet");
            } catch (IOException e) {
                if (!sock.isClosed()) {
                    LOG.warn("Unexpected exception at " + this, e);
                    try {
                        // this will cause everything to shutdown on
                        // this learner handler and will help notify
                        // the learner/observer instantaneously
                        sock.close();
                    } catch (IOException ie) {
                        LOG.warn("Error closing socket for handler " + this, ie);
                    }
                }
                break;
            }
        }
    }

    static public String packetToString(QuorumPacket p) {
        String type;
        String mess = null;

        switch (p.getType()) {
            case Leader.ACK:
                type = "ACK";
                break;
            case Leader.COMMIT:
                type = "COMMIT";
                break;
            case Leader.FOLLOWERINFO:
                type = "FOLLOWERINFO";
                break;
            case Leader.NEWLEADER:
                type = "NEWLEADER";
                break;
            case Leader.PING:
                type = "PING";
                break;
            case Leader.PROPOSAL:
                type = "PROPOSAL";
                TxnHeader hdr = new TxnHeader();
                try {
                    SerializeUtils.deserializeTxn(p.getData(), hdr);
                    // mess = "transaction: " + txn.toString();
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }
                break;
            case Leader.REQUEST:
                type = "REQUEST";
                break;
            case Leader.REVALIDATE:
                type = "REVALIDATE";
                ByteArrayInputStream bis = new ByteArrayInputStream(p.getData());
                DataInputStream dis = new DataInputStream(bis);
                try {
                    long id = dis.readLong();
                    mess = " sessionid = " + id;
                } catch (IOException e) {
                    LOG.warn("Unexpected exception", e);
                }

                break;
            case Leader.UPTODATE:
                type = "UPTODATE";
                break;
            case Leader.DIFF:
                type = "DIFF";
                break;
            case Leader.TRUNC:
                type = "TRUNC";
                break;
            case Leader.SNAP:
                type = "SNAP";
                break;
            case Leader.ACKEPOCH:
                type = "ACKEPOCH";
                break;
            case Leader.SYNC:
                type = "SYNC";
                break;
            case Leader.INFORM:
                type = "INFORM";
                break;
            case Leader.COMMITANDACTIVATE:
                type = "COMMITANDACTIVATE";
                break;
            case Leader.INFORMANDACTIVATE:
                type = "INFORMANDACTIVATE";
                break;
            default:
                type = "UNKNOWN" + p.getType();
        }
        String entry = null;
        if (type != null) {
            entry = type + " " + Long.toHexString(p.getZxid()) + " " + mess;
        }
        return entry;
    }

    /**
     * This thread will receive packets from the peer and process them and
     * also listen to new connections from new peers.
     */
    @Override
    public void run() {
        try {
            leader.addLearnerHandler(this);
            tickOfNextAckDeadline = leader.self.tick.get() + leader.self.initLimit + leader.self.syncLimit;
            ia = BinaryInputArchive.getArchive(bufferedInput);
            bufferedOutput = new BufferedOutputStream(sock.getOutputStream());
            oa = BinaryOutputArchive.getArchive(bufferedOutput);

            /**
             * 第一步.接收Learner发送的数据包，该数据包主要包含Learner节点的sid、protocolVersion、type(区分Follower还是Observer)和epoch
             *    该数据包主要功能：获取到集群中过半节点的epoch，然后找出一个最大的epoch，新epoch就是在这个最大epoch的基础上加1
             */
            QuorumPacket qp = new QuorumPacket();
            ia.readRecord(qp, "packet");
            if (qp.getType() != Leader.FOLLOWERINFO && qp.getType() != Leader.OBSERVERINFO) {
                LOG.error("First packet " + qp.toString() + " is not FOLLOWERINFO or OBSERVERINFO!");
                return;
            }

            byte learnerInfoData[] = qp.getData();
            if (learnerInfoData != null) {
                ByteBuffer bbsid = ByteBuffer.wrap(learnerInfoData);
                if (learnerInfoData.length >= 8) {
                    this.sid = bbsid.getLong();
                }
                if (learnerInfoData.length >= 12) {
                    this.version = bbsid.getInt(); // protocolVersion
                }
                if (learnerInfoData.length >= 20) {
                    long configVersion = bbsid.getLong();
                    if (configVersion > leader.self.getQuorumVerifier().getVersion()) {
                        throw new IOException("Follower is ahead of the leader (has a later activated configuration)");
                    }
                }
            } else {
                this.sid = leader.followerCounter.getAndDecrement();
            }

            if (leader.self.getView().containsKey(this.sid)) {
                LOG.info("Follower sid: " + this.sid + " : info : "
                        + leader.self.getView().get(this.sid).toString());
            } else {
                LOG.info("Follower sid: " + this.sid + " not in the current config " + Long.toHexString(leader.self.getQuorumVerifier().getVersion()));
            }

            if (qp.getType() == Leader.OBSERVERINFO) {
                learnerType = LearnerType.OBSERVER;
            }

            /**获取对端发送的epoch，取最高32位*/
            long lastAcceptedEpoch = ZxidUtils.getEpochFromZxid(qp.getZxid());

            long peerLastZxid;
            StateSummary ss = null;
            long zxid = qp.getZxid();
            /**计算新的epoch，该方法会阻塞，直到集群中过半的Follower节点同步完epoch并计算出一个新的epoch才会继续向下执行*/
            long newEpoch = leader.getEpochToPropose(this.getSid(), lastAcceptedEpoch);
            /**生成zid,计数器从0开始*/
            long newLeaderZxid = ZxidUtils.makeZxid(newEpoch, 0);

            /**
             * 第二步：同步新epoch，并把leader相关信息广播出去
             */
            if (this.getVersion() < 0x10000) {
                long epoch = ZxidUtils.getEpochFromZxid(zxid);
                ss = new StateSummary(epoch, zxid);
                leader.waitForEpochAck(this.getSid(), ss);
            } else {
                byte ver[] = new byte[4];
                ByteBuffer.wrap(ver).putInt(0x10000);
                /**将leader信息同步出去*/
                QuorumPacket newEpochPacket = new QuorumPacket(Leader.LEADERINFO, newLeaderZxid, ver, null);
                oa.writeRecord(newEpochPacket, "packet");
                bufferedOutput.flush();
                QuorumPacket ackEpochPacket = new QuorumPacket();
                ia.readRecord(ackEpochPacket, "packet");
                if (ackEpochPacket.getType() != Leader.ACKEPOCH) {
                    LOG.error(ackEpochPacket.toString()
                            + " is not ACKEPOCH");
                    return;
                }
                ByteBuffer bbepoch = ByteBuffer.wrap(ackEpochPacket.getData());
                ss = new StateSummary(bbepoch.getInt(), ackEpochPacket.getZxid());
                /**将新的epoch广播给所有的learner，该方法会阻塞(会有超时时间),直到新epoch广播到集群中，并收到一半以上节点ack反馈时才会继续向下执行*/
                leader.waitForEpochAck(this.getSid(), ss);
            }
            peerLastZxid = ss.getLastZxid();

            /**
             * 第三步：数据同步，同步流程:
             *   1.根据Learner节点的lastZxid和Leader节点的lastZxid对比，确定同步方式DIFF、SNAP、TRUNC以及DIFF+TRUNC
             *   2.数据同步Leader发送数据包大致分为三类：先发送同步方式数据包(DIFF+SNAP+TRUNC)、然后发送同步Proposal+Committed数据包、最后发送NewLeader数据包表示Leader已经将同步数据发送完成
             *   3.Learner接收到同步方式后进行相关的准备工作，然后根据Proposal+Committed进行同步，最后收到NewLeader表示同步数据包发送完毕了，需要对NewLeader数据包进行回复Ack数据包
             *   4.waitForNewLeaderAck()方法功能就是进入阻塞直到收到过半Follower节点回复的ack(包括自身)才会继续向下执行
             */
            boolean needSnap = syncFollower(peerLastZxid, leader.zk.getZKDatabase(), leader);

            /**全量同步*/
            if (needSnap) {
                boolean exemptFromThrottle = getLearnerType() != LearnerType.OBSERVER;
                LearnerSnapshot snapshot = leader.getLearnerSnapshotThrottler().beginSnapshot(exemptFromThrottle);
                try {
                    long zxidToSend = leader.zk.getZKDatabase().getDataTreeLastProcessedZxid();
                    oa.writeRecord(new QuorumPacket(Leader.SNAP, zxidToSend, null, null), "packet");
                    bufferedOutput.flush();
                    // Dump data to peer
                    leader.zk.getZKDatabase().serializeSnapshot(oa);
                    oa.writeString("BenWasHere", "signature");
                    bufferedOutput.flush();
                } finally {
                    snapshot.close();
                }
            }

            if (getVersion() < 0x10000) {
                QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER,
                        newLeaderZxid, null, null);
                oa.writeRecord(newLeaderQP, "packet");
            } else {
                /**数据同步完成之后会发送 NEWLEADER 信息*/
                QuorumPacket newLeaderQP = new QuorumPacket(Leader.NEWLEADER, newLeaderZxid, leader.self.getLastSeenQuorumVerifier().toString().getBytes(), null);
                queuedPackets.add(newLeaderQP);
            }
            bufferedOutput.flush();

            /**leader发送数据包并等待learner响应*/
            startSendingPackets();

            qp = new QuorumPacket();
            ia.readRecord(qp, "packet");
            if (qp.getType() != Leader.ACK) {
                return;
            }

            /**阻塞，等待集群中过半节点都应答*/
            leader.waitForNewLeaderAck(getSid(), qp.getZxid());

            /**同步跟踪检测,同一时刻只会跟踪一个提议*/
            syncLimitCheck.start();

            sock.setSoTimeout(leader.self.tickTime * leader.self.syncLimit);

            /**当leader服务器不在运行状态且线程中断,会进行超时等待,超时后会再次选举*/
            synchronized (leader.zk) {
                while (!leader.zk.isRunning() && !this.isInterrupted()) {
                    leader.zk.wait(20);
                }
            }
            /**leader向follower发送 UPTODATE,告知其可对外提供服务*/
            queuedPackets.add(new QuorumPacket(Leader.UPTODATE, -1, null, null));

            while (true) {
                qp = new QuorumPacket();
                ia.readRecord(qp, "packet");

                long traceMask = ZooTrace.SERVER_PACKET_TRACE_MASK;
                if (qp.getType() == Leader.PING) {
                    traceMask = ZooTrace.SERVER_PING_TRACE_MASK;
                }
                if (LOG.isTraceEnabled()) {
                    ZooTrace.logQuorumPacket(LOG, traceMask, 'i', qp);
                }
                tickOfNextAckDeadline = leader.self.tick.get() + leader.self.syncLimit;


                ByteBuffer bb;
                long sessionId;
                int cxid;
                int type;

                /**根据接收到的数据包类型处理不同的逻辑*/
                switch (qp.getType()) {
                    case Leader.ACK:
                        if (this.learnerType == LearnerType.OBSERVER) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Received ACK from Observer  " + this.sid);
                            }
                        }
                        syncLimitCheck.updateAck(qp.getZxid());
                        leader.processAck(this.sid, qp.getZxid(), sock.getLocalSocketAddress());
                        break;
                    case Leader.PING:
                        // Process the touches
                        ByteArrayInputStream bis = new ByteArrayInputStream(qp
                                .getData());
                        DataInputStream dis = new DataInputStream(bis);
                        while (dis.available() > 0) {
                            long sess = dis.readLong();
                            int to = dis.readInt();
                            leader.zk.touch(sess, to);
                        }
                        break;
                    case Leader.REVALIDATE:
                        bis = new ByteArrayInputStream(qp.getData());
                        dis = new DataInputStream(bis);
                        long id = dis.readLong();
                        int to = dis.readInt();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        DataOutputStream dos = new DataOutputStream(bos);
                        dos.writeLong(id);
                        boolean valid = leader.zk.checkIfValidGlobalSession(id, to);
                        if (valid) {
                            try {
                                //set the session owner
                                // as the follower that
                                // owns the session
                                leader.zk.setOwner(id, this);
                            } catch (SessionExpiredException e) {
                                LOG.error("Somehow session " + Long.toHexString(id) +
                                        " expired right after being renewed! (impossible)", e);
                            }
                        }
                        if (LOG.isTraceEnabled()) {
                            ZooTrace.logTraceMessage(LOG,
                                    ZooTrace.SESSION_TRACE_MASK,
                                    "Session 0x" + Long.toHexString(id)
                                            + " is valid: " + valid);
                        }
                        dos.writeBoolean(valid);
                        qp.setData(bos.toByteArray());
                        queuedPackets.add(qp);
                        break;
                    case Leader.REQUEST:
                        bb = ByteBuffer.wrap(qp.getData());
                        sessionId = bb.getLong();
                        cxid = bb.getInt();
                        type = bb.getInt();
                        bb = bb.slice();
                        Request si;
                        if (type == OpCode.sync) {
                            si = new LearnerSyncRequest(this, sessionId, cxid, type, bb, qp.getAuthinfo());
                        } else {
                            si = new Request(null, sessionId, cxid, type, bb, qp.getAuthinfo());
                        }
                        si.setOwner(this);
                        leader.zk.submitLearnerRequest(si);
                        break;
                    default:
                        LOG.warn("unexpected quorum packet, type: {}", packetToString(qp));
                        break;
                }
            }
        } catch (IOException e) {
            if (sock != null && !sock.isClosed()) {
                LOG.error("Unexpected exception causing shutdown while sock "
                        + "still open", e);
                //close the socket to make sure the
                //other side can see it being close
                try {
                    sock.close();
                } catch (IOException ie) {
                    // do nothing
                }
            }
        } catch (InterruptedException e) {
            LOG.error("Unexpected exception causing shutdown", e);
        } catch (SnapshotThrottleException e) {
            LOG.error("too many concurrent snapshots: " + e);
        } finally {
            LOG.warn("******* GOODBYE "
                    + (sock != null ? sock.getRemoteSocketAddress() : "<null>")
                    + " ********");
            shutdown();
        }
    }

    /**
     * Start thread that will forward any packet in the queue to the follower
     */
    protected void startSendingPackets() {
        if (!sendingThreadStarted) {
            // Start sending packets
            new Thread() {
                public void run() {
                    Thread.currentThread().setName(
                            "Sender-" + sock.getRemoteSocketAddress());
                    try {
                        sendPackets();
                    } catch (InterruptedException e) {
                        LOG.warn("Unexpected interruption " + e.getMessage());
                    }
                }
            }.start();
            sendingThreadStarted = true;
        } else {
            LOG.error("Attempting to start sending thread after it already started");
        }
    }

    /**
     * Determine if we need to sync with follower using DIFF/TRUNC/SNAP
     * and setup follower to receive packets from commit processor
     *
     * @param peerLastZxid
     * @param db
     * @param leader
     * @return true if snapshot transfer is needed.
     */
    public boolean syncFollower(long peerLastZxid, ZKDatabase db, Leader leader) {
        /**
         * peerLastZxid:该Learner服务器最后处理的ZXID
         * minCommittedLog:Leader服务器提议缓存队列committedLog中的最小ZXID
         * maxCommittedLog:Leader服务器提议缓存队列committedLog中的最大ZXID
         * Leader端会在zk服务器上维护一个committedLog集合，该集合保存了最近事务请求的议案Proposal，默认保存最近500条；
         */
        boolean isPeerNewEpochZxid = (peerLastZxid & 0xffffffffL) == 0;
        long currentZxid = peerLastZxid;
        boolean needSnap = true;
        boolean txnLogSyncEnabled = db.isTxnLogSyncEnabled();
        ReentrantReadWriteLock lock = db.getLogLock();
        ReadLock rl = lock.readLock();
        rl.lock();
        try {
            long maxCommittedLog = db.getmaxCommittedLog();
            long minCommittedLog = db.getminCommittedLog();
            long lastProcessedZxid = db.getDataTreeLastProcessedZxid();
            if (db.getCommittedLog().isEmpty()) {
                minCommittedLog = lastProcessedZxid;
                maxCommittedLog = lastProcessedZxid;
            }

            /**由参数zookeeper.forceSnapshotSync空值,默认为false*/
            if (forceSnapSync) {
                LOG.warn("Forcing snapshot sync - should not see this in production");
            } else if (lastProcessedZxid == peerLastZxid) {
                /**当zxid一致时，发送一个空的packet,follower 与 leader 的 zxid 相同说明 二者数据一致；同步方式为差量同步 DIFF，同步的zxid 为 peerLastZxid， 也就是不需要同步*/
                LOG.info("Sending DIFF zxid=0x" + Long.toHexString(peerLastZxid) +
                        " for peer sid: " + getSid());
                queueOpPacket(Leader.DIFF, peerLastZxid);
                needOpPacket = false;
                needSnap = false;
            } else if (peerLastZxid > maxCommittedLog && !isPeerNewEpochZxid) {
                /**回滚同步(TRUNC同步) follower的zxid比leader 大,则告诉 follower 执行TRUNC+回滚*/
                queueOpPacket(Leader.TRUNC, maxCommittedLog);
                currentZxid = maxCommittedLog;
                needOpPacket = false;
                needSnap = false;
            } else if ((maxCommittedLog >= peerLastZxid) && (minCommittedLog <= peerLastZxid)) {
                /**差异化同步（DIFF同步）,peerLastZxid 介于 minCommittedLog~maxCommittedLog中间,这里有一种场景是learner上有的数据不在leader上,那么这时需要先进行trunc截断*/
                Iterator<Proposal> itr = db.getCommittedLog().iterator();
                currentZxid = queueCommittedProposals(itr, peerLastZxid, null, maxCommittedLog);
                needSnap = false;
            } else if (peerLastZxid < minCommittedLog && txnLogSyncEnabled) {
                /**全量同步,将Leader服务器上的全量内存数据构建一个快照snapshot，同步给learner*/
                long sizeLimit = db.calculateTxnLogSizeLimit();
                Iterator<Proposal> txnLogItr = db.getProposalsFromTxnLog(peerLastZxid, sizeLimit);
                if (txnLogItr.hasNext()) {
                    currentZxid = queueCommittedProposals(txnLogItr, peerLastZxid, minCommittedLog, maxCommittedLog);
                    Iterator<Proposal> committedLogItr = db.getCommittedLog().iterator();
                    currentZxid = queueCommittedProposals(committedLogItr, currentZxid, null, maxCommittedLog);
                    needSnap = false;
                }
                // closing the resources
                if (txnLogItr instanceof TxnLogProposalIterator) {
                    TxnLogProposalIterator txnProposalItr = (TxnLogProposalIterator) txnLogItr;
                    txnProposalItr.close();
                }
            } else {
                LOG.warn("Unhandled scenario for peer sid: " + getSid());
            }
            LOG.debug("Start forwarding 0x" + Long.toHexString(currentZxid) +
                    " for peer sid: " + getSid());
            leaderLastZxid = leader.startForwarding(this, currentZxid);
        } finally {
            rl.unlock();
        }

        if (needOpPacket && !needSnap) {
            needSnap = true;
        }

        return needSnap;
    }

    /**
     * Queue committed proposals into packet queue. The range of packets which
     * is going to be queued are (peerLaxtZxid, maxZxid]
     *
     * @param itr               iterator point to the proposals
     * @param peerLastZxid      last zxid seen by the follower
     * @param maxZxid           max zxid of the proposal to queue, null if no limit
     * @param lastCommittedZxid when sending diff, we need to send lastCommittedZxid
     *                          on the leader to follow Zab 1.0 protocol.
     * @return last zxid of the queued proposal
     */
    protected long queueCommittedProposals(Iterator<Proposal> itr,
                                           long peerLastZxid, Long maxZxid, Long lastCommittedZxid) {
        boolean isPeerNewEpochZxid = (peerLastZxid & 0xffffffffL) == 0;
        long queuedZxid = peerLastZxid;
        /**在遍历 proposals 时，用来记录上一个 proposal 的 zxid*/
        long prevProposalZxid = -1;
        while (itr.hasNext()) {
            Proposal propose = itr.next();
            long packetZxid = propose.packet.getZxid();
            if ((maxZxid != null) && (packetZxid > maxZxid)) {
                break;
            }

            /**跳过 follower 已经存在的提案
             * DIFF差异化同步,leader和follower之间的交互流程;
             * 1.发送DIFF指令
             * 2.发送PROPOSAL (如果leader上不存在follower的数据,会先发送trunc指令)
             * 3.发送COMMIT
             * 4.发送PROPOSAL
             * 5.发送COMMIT
             * */
            if (packetZxid < peerLastZxid) {
                prevProposalZxid = packetZxid;
                continue;
            }
            if (needOpPacket) {
                if (packetZxid == peerLastZxid) {
                    queueOpPacket(Leader.DIFF, lastCommittedZxid);
                    needOpPacket = false;
                    continue;
                }

                if (isPeerNewEpochZxid) {
                    queueOpPacket(Leader.DIFF, lastCommittedZxid);
                    needOpPacket = false;
                } else if (packetZxid > peerLastZxid) {
                    if (ZxidUtils.getEpochFromZxid(packetZxid) != ZxidUtils.getEpochFromZxid(peerLastZxid)) {
                        return queuedZxid;
                    }
                    /**
                     * 此时说明有部分 proposals 提案在 leader 节点上不存在，则需告诉 follower 丢弃这部分 proposals
                     * 也就是告诉 follower 先执行回滚 TRUNC ，需要回滚到 prevProposalZxid 处，也就是 follower 需要丢弃 prevProposalZxid ~ peerLastZxid 范围内的数据
                     * 剩余的 proposals 则通过 DIFF 进行同步
                     */
                    queueOpPacket(Leader.TRUNC, prevProposalZxid);
                    needOpPacket = false;
                }
            }

            if (packetZxid <= queuedZxid) {
                continue;
            }


            queuePacket(propose.packet);
            queueOpPacket(Leader.COMMIT, packetZxid);
            queuedZxid = packetZxid;

        }

        if (needOpPacket && isPeerNewEpochZxid) {
            queueOpPacket(Leader.DIFF, lastCommittedZxid);
            needOpPacket = false;
        }

        return queuedZxid;
    }

    public void shutdown() {
        // Send the packet of death
        try {
            queuedPackets.put(proposalOfDeath);
        } catch (InterruptedException e) {
            LOG.warn("Ignoring unexpected exception", e);
        }
        try {
            if (sock != null && !sock.isClosed()) {
                sock.close();
            }
        } catch (IOException e) {
            LOG.warn("Ignoring unexpected exception during socket close", e);
        }
        this.interrupt();
        leader.removeLearnerHandler(this);
    }

    public long tickOfNextAckDeadline() {
        return tickOfNextAckDeadline;
    }

    /**
     * ping calls from the leader to the peers
     */
    public void ping() {
        // If learner hasn't sync properly yet, don't send ping packet
        // otherwise, the learner will crash
        if (!sendingThreadStarted) {
            return;
        }
        long id;
        if (syncLimitCheck.check(System.nanoTime())) {
            synchronized (leader) {
                id = leader.lastProposed;
            }
            QuorumPacket ping = new QuorumPacket(Leader.PING, id, null, null);
            queuePacket(ping);
        } else {
            LOG.warn("Closing connection to peer due to transaction timeout.");
            shutdown();
        }
    }

    /**
     * Queue leader packet of a given type
     *
     * @param type
     * @param zxid
     */
    private void queueOpPacket(int type, long zxid) {
        QuorumPacket packet = new QuorumPacket(type, zxid, null, null);
        queuePacket(packet);
    }

    void queuePacket(QuorumPacket p) {
        queuedPackets.add(p);
    }

    public boolean synced() {
        return isAlive() && leader.self.tick.get() <= tickOfNextAckDeadline;
    }

    /**
     * For testing, return packet queue
     *
     * @return
     */
    public Queue<QuorumPacket> getQueuedPackets() {
        return queuedPackets;
    }

    /**
     * For testing, we need to reset this value
     */
    public void setFirstPacket(boolean value) {
        needOpPacket = value;
    }
}
