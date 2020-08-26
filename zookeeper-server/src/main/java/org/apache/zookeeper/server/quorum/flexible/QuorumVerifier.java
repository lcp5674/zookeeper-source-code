/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum.flexible;

import java.util.Set;
import java.util.Map;

import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;

/**
 * All quorum validators have to implement a method called
 * containsQuorum, which verifies if a HashSet of server 
 * identifiers constitutes a quorum.
 * 用于检查一个服务器列表能否构成一个可用的服务器集群
 */

public interface QuorumVerifier {
    long getWeight(long id);

    /**
     * 选举的结果是否超过集群半数节点
     * @param set
     * @return
     */
    boolean containsQuorum(Set<Long> set);
    long getVersion();
    void setVersion(long ver);

    /**
     * 获取集群中所有节点
     * @return
     */
    Map<Long, QuorumServer> getAllMembers();

    /**
     * 获取集群中参与选举的节点
     * @return
     */
    Map<Long, QuorumServer> getVotingMembers();

    /**
     * 获取集群中Observer节点
     * @return
     */
    Map<Long, QuorumServer> getObservingMembers();
    boolean equals(Object o);
    String toString();
}
