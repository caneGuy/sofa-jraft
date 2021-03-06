/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft.core;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.alipay.sofa.jraft.CliService;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.NodeManager;
import com.alipay.sofa.jraft.RouteTable;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.CliOptions;
import com.alipay.sofa.jraft.test.TestUtils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CliServiceTest {

    private String        dataPath;

    private TestCluster   cluster;
    private final String  groupId = "CliServiceTest";

    private CliService    cliService;

    private Configuration conf;

    @Before
    public void setup() throws Exception {

        this.dataPath = TestUtils.mkTempDir();
        FileUtils.forceMkdir(new File(this.dataPath));
        assertEquals(NodeImpl.GLOBAL_NUM_NODES.get(), 0);
        final List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster(groupId, dataPath, peers);
        for (final PeerId peer : peers) {
            cluster.start(peer.getEndpoint());
        }
        cluster.waitLeader();

        cliService = new CliServiceImpl();
        this.conf = new Configuration(peers);
        assertTrue(cliService.init(new CliOptions()));
    }

    @After
    public void teardown() throws Exception {
        cliService.shutdown();
        cluster.stopAll();
        if (NodeImpl.GLOBAL_NUM_NODES.get() > 0) {
            Thread.sleep(1000);
            assertEquals(NodeImpl.GLOBAL_NUM_NODES.get(), 0);
        }
        FileUtils.deleteDirectory(new File(this.dataPath));
        NodeManager.getInstance().clear();
        RouteTable.getInstance().reset();
    }

    @Test
    public void testTransferLeader() throws Exception {
        final PeerId leader = cluster.getLeader().getNodeId().getPeerId().copy();
        assertNotNull(leader);

        final Set<PeerId> peers = conf.getPeerSet();
        PeerId targetPeer = null;
        for (final PeerId peer : peers) {
            if (!peer.equals(leader)) {
                targetPeer = peer;
                break;
            }
        }
        assertNotNull(targetPeer);
        assertTrue(this.cliService.transferLeader(groupId, conf, targetPeer).isOk());
        cluster.waitLeader();
        assertEquals(targetPeer, cluster.getLeader().getNodeId().getPeerId());
    }

    @SuppressWarnings("SameParameterValue")
    private void sendTestTaskAndWait(Node node, int code) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            final ByteBuffer data = ByteBuffer.wrap(("hello" + i).getBytes());
            final Task task = new Task(data, new ExpectClosure(code, null, latch));
            node.apply(task);
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testAddPeerRemovePeer() throws Exception {
        final PeerId peer3 = new PeerId(TestUtils.getMyIp(), TestUtils.INIT_PORT + 3);
        assertTrue(cluster.start(peer3.getEndpoint()));
        sendTestTaskAndWait(cluster.getLeader(), 0);
        Thread.sleep(100);
        assertEquals(0, cluster.getFsms().get(3).getLogs().size());

        assertTrue(this.cliService.addPeer(groupId, conf, peer3).isOk());
        Thread.sleep(100);
        assertEquals(10, cluster.getFsms().get(3).getLogs().size());
        sendTestTaskAndWait(cluster.getLeader(), 0);
        Thread.sleep(100);
        assertEquals(4, cluster.getFsms().size());
        for (final MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(20, fsm.getLogs().size());
        }

        //remove peer3
        assertTrue(this.cliService.removePeer(groupId, conf, peer3).isOk());
        Thread.sleep(200);
        sendTestTaskAndWait(cluster.getLeader(), 0);
        Thread.sleep(1000);
        assertEquals(4, cluster.getFsms().size());
        for (final MockStateMachine fsm : cluster.getFsms()) {
            if (fsm.getAddress().equals(peer3.getEndpoint())) {
                assertEquals(20, fsm.getLogs().size());
            } else {
                assertEquals(30, fsm.getLogs().size());
            }
        }
    }

    @Test
    public void testChangePeers() throws Exception {
        final List<PeerId> newPeers = TestUtils.generatePeers(10);
        newPeers.removeAll(conf.getPeerSet());
        for (final PeerId peer : newPeers) {
            assertTrue(cluster.start(peer.getEndpoint()));
        }

        final PeerId oldLeader = cluster.getLeader().getNodeId().getPeerId();
        assertNotNull(oldLeader);
        assertTrue(this.cliService.changePeers(groupId, conf, new Configuration(newPeers)).isOk());
        cluster.waitLeader();
        final PeerId newLeader = cluster.getLeader().getNodeId().getPeerId();
        assertNotEquals(oldLeader, newLeader);
        assertTrue(newPeers.contains(newLeader));
    }

    @Test
    public void testSnapshot() throws Exception {
        this.sendTestTaskAndWait(cluster.getLeader(), 0);
        assertEquals(3, cluster.getFsms().size());
        for (final MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(0, fsm.getSaveSnapshotTimes());
        }

        for (final PeerId peer : this.conf) {
            assertTrue(this.cliService.snapshot(groupId, peer).isOk());
        }
        Thread.sleep(1000);
        for (final MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(1, fsm.getSaveSnapshotTimes());
        }
    }

    @Test
    public void testGetPeers() throws Exception {
        PeerId leader = cluster.getLeader().getNodeId().getPeerId();
        assertNotNull(leader);
        assertArrayEquals(conf.getPeerSet().toArray(), new HashSet<>(this.cliService.getPeers(groupId, conf)).toArray());

        // stop one peer
        final List<PeerId> peers = this.conf.getPeers();
        cluster.stop(peers.get(0).getEndpoint());

        cluster.waitLeader();

        leader = cluster.getLeader().getNodeId().getPeerId();
        assertNotNull(leader);
        assertArrayEquals(conf.getPeerSet().toArray(), new HashSet<>(this.cliService.getPeers(groupId, conf)).toArray());

        cluster.stopAll();

        try {
            this.cliService.getPeers(groupId, conf);
            fail();
        } catch (final IllegalStateException e) {
            assertEquals("Fail to get leader of group " + this.groupId, e.getMessage());
        }
    }

    @Test
    public void testGetAlivePeers() throws Exception {
        PeerId leader = this.cluster.getLeader().getNodeId().getPeerId();
        assertNotNull(leader);
        assertArrayEquals(this.conf.getPeerSet().toArray(),
            new HashSet<>(this.cliService.getAlivePeers(this.groupId, this.conf)).toArray());

        // stop one peer
        final List<PeerId> peers = this.conf.getPeers();
        this.cluster.stop(peers.get(0).getEndpoint());
        peers.remove(0);

        this.cluster.waitLeader();

        Thread.sleep(1000);

        leader = this.cluster.getLeader().getNodeId().getPeerId();
        assertNotNull(leader);
        assertArrayEquals(new HashSet<>(peers).toArray(),
            new HashSet<>(this.cliService.getAlivePeers(this.groupId, this.conf)).toArray());

        this.cluster.stopAll();

        try {
            this.cliService.getAlivePeers(this.groupId, this.conf);
            fail();
        } catch (final IllegalStateException e) {
            assertEquals("Fail to get leader of group " + this.groupId, e.getMessage());
        }
    }
}
