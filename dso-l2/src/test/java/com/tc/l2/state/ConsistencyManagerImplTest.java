/*
 *
 *  The contents of this file are subject to the Terracotta Public License Version
 *  2.0 (the "License"); You may not use this file except in compliance with the
 *  License. You may obtain a copy of the License at
 *
 *  http://terracotta.org/legal/terracotta-public-license.
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 *  the specific language governing rights and limitations under the License.
 *
 *  The Covered Software is Terracotta Core.
 *
 *  The Initial Developer of the Covered Software is
 *  Terracotta, Inc., a Software AG company
 *
 */
package com.tc.l2.state;

import com.tc.net.NodeID;
import com.tc.objectserver.impl.JMXSubsystem;
import com.tc.util.Assert;

import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.terracotta.config.Consistency;
import org.terracotta.config.FailoverPriority;
import org.terracotta.config.Servers;
import org.terracotta.config.TcConfig;
import org.terracotta.config.Voter;

/**
 *
 */
public class ConsistencyManagerImplTest {

  @Rule
  public final ExpectedSystemExit exit = ExpectedSystemExit.none();

  public ConsistencyManagerImplTest() {
  }
  
  @BeforeClass
  public static void setUpClass() {
  }
  
  @AfterClass
  public static void tearDownClass() {
  }
  
  @Before
  public void setUp() {
  }
  
  @After
  public void tearDown() {
  }

  /**
   * Test of requestTransition method, of class ConsistencyManagerImpl.
   */
  @Test
  public void testVoteThreshold() throws Exception {
    String voter = UUID.randomUUID().toString();
    ConsistencyManagerImpl impl = new ConsistencyManagerImpl(1, 1);
    JMXSubsystem caller = new JMXSubsystem();
    caller.call(ServerVoterManager.MBEAN_NAME, "registerVoter", voter);
    long term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "heartbeat", voter));
    Assert.assertTrue(term == 0);
    new Thread(()->{
      long response = 0;
      while (response >= 0) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
          throw new RuntimeException(ie);
        }
        response = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "heartbeat", voter));
      }
    }).start();
    boolean allowed = impl.requestTransition(ServerMode.PASSIVE, mock(NodeID.class), ConsistencyManager.Transition.MOVE_TO_ACTIVE);
    term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "heartbeat", voter));
    Assert.assertTrue(term > 0);
    Assert.assertFalse(allowed);
    term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "vote", voter + ":" + term));
    Assert.assertTrue(term == 0);
    term = Long.parseLong(caller.call(ServerVoterManager.MBEAN_NAME, "registerVoter", UUID.randomUUID().toString()));
    Assert.assertTrue(term < 0);
    allowed = impl.requestTransition(ServerMode.PASSIVE, mock(NodeID.class), ConsistencyManager.Transition.MOVE_TO_ACTIVE);
    Assert.assertTrue(allowed);
    Assert.assertTrue(Boolean.parseBoolean(caller.call(ServerVoterManager.MBEAN_NAME, "deregisterVoter", voter)));
  }
  
  @Test
  public void testVoteConfig() throws Exception {
    List serverList = mock(List.class);
    when(serverList.size()).thenReturn(2);
    Servers servers = mock(Servers.class);
    when(servers.getServer()).thenReturn(serverList);
    TcConfig conf = mock(TcConfig.class);
    when(conf.getServers()).thenReturn(servers);
    FailoverPriority fail = mock(FailoverPriority.class);
    String avail = "Availability";
    when(fail.getAvailability()).thenReturn(avail);
    when(conf.getFailoverPriority()).thenReturn(fail);
    
    Assert.assertEquals(-1, ConsistencyManager.parseVoteCount(conf));
    
    when(conf.getFailoverPriority()).thenReturn(fail);
    
    Consistency c = mock(Consistency.class);
    Voter voter = mock(Voter.class);
    when(voter.getCount()).thenReturn(1);
    
    when(c.getVoter()).thenReturn(voter);
    when(fail.getConsistency()).thenReturn(c);
    when(fail.getAvailability()).thenReturn(null);
    
    Assert.assertEquals(1, ConsistencyManager.parseVoteCount(conf));
    when(voter.getCount()).thenReturn(2);
    
    Assert.assertEquals(2, ConsistencyManager.parseVoteCount(conf));

  }
  
  @Test
  public void testAddClientIsNotPersistent() throws Exception {
    ConsistencyManagerImpl impl = new ConsistencyManagerImpl(1, 1);
    long cterm = impl.getCurrentTerm();
    boolean granted = impl.requestTransition(ServerMode.ACTIVE, mock(NodeID.class), ConsistencyManager.Transition.ADD_CLIENT);
    Assert.assertFalse(granted);
    Assert.assertFalse(impl.isVoting());
    Assert.assertFalse(impl.isBlocked());
    Assert.assertEquals(cterm, impl.getCurrentTerm());
  }

  @Test
  public void testVoteConfigMandatoryForMultiNode() throws Exception {
    List serverList = mock(List.class);
    when(serverList.size()).thenReturn(2);
    Servers servers = mock(Servers.class);
    when(servers.getServer()).thenReturn(serverList);
    TcConfig conf = mock(TcConfig.class);
    when(conf.getServers()).thenReturn(servers);
    exit.expectSystemExitWithStatus(-1);
    ConsistencyManager.parseVoteCount(conf);
  }

  @Test
  public void testVoteConfigNotMandatoryForSingleNode() throws Exception {
    List serverList = mock(List.class);
    when(serverList.size()).thenReturn(1);
    Servers servers = mock(Servers.class);
    when(servers.getServer()).thenReturn(serverList);
    TcConfig conf = mock(TcConfig.class);
    when(conf.getServers()).thenReturn(servers);
    Assert.assertEquals(-1, ConsistencyManager.parseVoteCount(conf));
  }

}
