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

import java.util.ArrayList;
import java.util.HashSet;

import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;

/**
 * 用于保存集群和选举的映射关系
 */
public class SyncedLearnerTracker {

    protected ArrayList<QuorumVerifierAcksetPair> qvAcksetPairs =
            new ArrayList<QuorumVerifierAcksetPair>();

    /**
     * 增加集群信息
     *
     * @param qv
     */
    public void addQuorumVerifier(QuorumVerifier qv) {
        qvAcksetPairs.add(new QuorumVerifierAcksetPair(qv,
                new HashSet<Long>(qv.getVotingMembers().size())));
    }

    /**
     * 添加响应信息
     *
     * @param sid
     * @return
     */
    public boolean addAck(Long sid) {
        boolean change = false;
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (qvAckset.getQuorumVerifier().getVotingMembers().containsKey(sid)) {
                qvAckset.getAckset().add(sid);
                change = true;
            }
        }
        return change;
    }

    /**
     * 判断集群中参与选举的节点是否超过集群半数节点
     *
     * @return
     */
    public boolean hasAllQuorums() {
        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            if (!qvAckset.getQuorumVerifier().containsQuorum(qvAckset.getAckset()))
                return false;
        }
        return true;
    }

    public String ackSetsToString() {
        StringBuilder sb = new StringBuilder();

        for (QuorumVerifierAcksetPair qvAckset : qvAcksetPairs) {
            sb.append(qvAckset.getAckset().toString()).append(",");
        }

        return sb.substring(0, sb.length() - 1);
    }

    public static class QuorumVerifierAcksetPair {
        private final QuorumVerifier qv;
        private final HashSet<Long> ackset;

        public QuorumVerifierAcksetPair(QuorumVerifier qv, HashSet<Long> ackset) {
            this.qv = qv;
            this.ackset = ackset;
        }

        public QuorumVerifier getQuorumVerifier() {
            return this.qv;
        }

        public HashSet<Long> getAckset() {
            return this.ackset;
        }
    }
}
