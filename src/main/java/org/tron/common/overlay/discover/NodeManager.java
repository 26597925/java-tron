/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.tron.common.overlay.discover;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.tron.common.overlay.discover.message.*;
import org.tron.common.overlay.discover.table.NodeTable;
import org.tron.common.utils.CollectionUtils;
import org.tron.core.config.args.Args;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The central class for Peer Discovery machinery.
 *
 * The NodeManager manages info on all the Nodes discovered by the peer discovery protocol, routes
 * protocol messages to the corresponding NodeHandlers and supplies the info about discovered Nodes
 * and their usage statistics
 *
 * Created by Anton Nashatyrev on 16.07.2015.
 */
@Component
public class NodeManager implements Consumer<DiscoveryEvent> {

  static final org.slf4j.Logger logger = LoggerFactory.getLogger("NodeManager");

  private final boolean PERSIST;

//  @Autowired
//  private Manager dbManager;

  private static final long LISTENER_REFRESH_RATE = 1000;
  private static final long DB_COMMIT_RATE = 1 * 60 * 1000;
  static final int MAX_NODES = 2000;
  static final int NODES_TRIM_THRESHOLD = 3000;

  //PeerConnectionTester peerConnectionManager;
  //EthereumListener ethereumListener;

  Consumer<DiscoveryEvent> messageSender;

  NodeTable table;
  private Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();
  //final ECKey key;
  final Node homeNode;
  private List<Node> bootNodes;

  // option to handle inbounds only from known peers (i.e. which were discovered by ourselves)
  boolean inboundOnlyFromKnownNodes = false;

  private boolean discoveryEnabled;

  private Map<DiscoverListener, ListenerHandler> listeners = new IdentityHashMap<>();

  private boolean inited = false;
  private Timer logStatsTimer = new Timer();
  private Timer nodeManagerTasksTimer = new Timer("NodeManagerTasks");
  private ScheduledExecutorService pongTimer;

  @Autowired
  public NodeManager(ApplicationContext tx) {
    Args args = Args.getInstance();
    PERSIST = args.isNodeDiscoveryPersist();

    discoveryEnabled = args.isNodeDiscoveryEnable();
    args.nodeId();
    homeNode = Node.instanceOf("127.0.0.1:"+ args.getNodeListenPort());//new Node(args.nodeId(), "127.0.0.1", args.getNodeListenPort());
//    homeNode = dbManager.getHomeNode();
//
//    logger.info("homeNode : {}", homeNode.toString());
//
    table = new NodeTable(homeNode, args.isNodeDiscoveryPublicHomeNode());

    logStatsTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        logger.trace("Statistics:\n {}", dumpAllStatistics());
      }
    }, 1 * 1000, 60 * 1000);

    this.pongTimer = Executors.newSingleThreadScheduledExecutor();

    for (Node node : args.getNodeActive()) {
      getNodeHandler(node).getNodeStatistics().setPredefined(true);
    }
  }

  public ScheduledExecutorService getPongTimer() {
    return pongTimer;
  }

  void setBootNodes(List<Node> bootNodes) {
    this.bootNodes = bootNodes;
  }

  void channelActivated() {
    // channel activated now can send messages
    if (!inited) {
      // no another init on a new channel activation
      inited = true;

      // this task is done asynchronously with some fixed rate
      // to avoid any overhead in the NodeStatistics classes keeping them lightweight
      // (which might be critical since they might be invoked from time critical sections)
      nodeManagerTasksTimer.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          processListeners();
        }
      }, LISTENER_REFRESH_RATE, LISTENER_REFRESH_RATE);

      for (Node node : bootNodes) {
        getNodeHandler(node);
      }
    }
  }

  public void setMessageSender(Consumer<DiscoveryEvent> messageSender) {
    this.messageSender = messageSender;
  }

  private String getKey(Node n) {
    return getKey(new InetSocketAddress(n.getHost(), n.getPort()));
  }

  private String getKey(InetSocketAddress address) {
    InetAddress addr = address.getAddress();
    // addr == null if the hostname can't be resolved
    return (addr == null ? address.getHostString() : addr.getHostAddress()) + ":" + address
        .getPort();
  }

  public synchronized NodeHandler getNodeHandler(Node n) {
    String key = getKey(n);
    NodeHandler ret = nodeHandlerMap.get(key);
    if (ret == null) {
      trimTable();
      ret = new NodeHandler(n, this);
      nodeHandlerMap.put(key, ret);
      logger.info(" +++ New node, {}: {} {} {}", nodeHandlerMap.size(),n.getHost(),n.getPort(), n.getHexIdShort());
//      if (!n.isDiscoveryNode() && !n.getHexId().equals(homeNode.getHexId())) {
//        //ethereumListener.onNodeDiscovered(ret.getNode());
//      }
    } else if (ret.getNode().isDiscoveryNode() && !n.isDiscoveryNode()) {
      // we found discovery node with same host:port,
      // replace node with correct nodeId
      ret.node = n;
//      if (!n.getHexId().equals(homeNode.getHexId())) {
//        //ethereumListener.onNodeDiscovered(ret.getNode());
//      }
      logger.debug(" +++ Found real nodeId", n.getHost(),n.getPort(), n.getHexIdShort());
    }

    return ret;
  }

  private void trimTable() {
    if (nodeHandlerMap.size() > NODES_TRIM_THRESHOLD) {

      List<NodeHandler> sorted = new ArrayList<>(nodeHandlerMap.values());
      // reverse sort by reputation
      sorted.sort((o1, o2) -> o1.getNodeStatistics().getReputation() - o2.getNodeStatistics().getReputation());

      for (NodeHandler handler : sorted) {
        nodeHandlerMap.remove(getKey(handler.getNode()));
        if (nodeHandlerMap.size() <= MAX_NODES) {
          break;
        }
      }
    }
  }

  boolean hasNodeHandler(Node n) {
    return nodeHandlerMap.containsKey(getKey(n));
  }

  public NodeTable getTable() {
    return table;
  }

  public NodeStatistics getNodeStatistics(Node n) {
    return getNodeHandler(n).getNodeStatistics();
  }

  @Override
  public void accept(DiscoveryEvent discoveryEvent) {
    handleInbound(discoveryEvent);
  }

  public void handleInbound(DiscoveryEvent discoveryEvent) {
    Message m = discoveryEvent.getMessage();
    InetSocketAddress sender = discoveryEvent.getAddress();

    Node n = new Node(m.getNodeId(), sender.getHostString(), sender.getPort());

    if (inboundOnlyFromKnownNodes && !hasNodeHandler(n)) {
      logger.debug(
          "=/=> (" + sender + "): inbound packet from unknown peer rejected due to config option.");
      return;
    }
    NodeHandler nodeHandler = getNodeHandler(n);

    logger.trace("===> ({}) {} [{}] {}", sender, m.getClass().getSimpleName(), nodeHandler, m);

    byte type = m.getType();
    switch (type) {
      case 1:
        nodeHandler.handlePing((PingMessage) m);
        break;
      case 2:
        nodeHandler.handlePong((PongMessage) m);
        break;
      case 3:
        nodeHandler.handleFindNode((FindNodeMessage) m);
        break;
      case 4:
        nodeHandler.handleNeighbours((NeighborsMessage) m);
        break;
    }
  }

  public void sendOutbound(DiscoveryEvent discoveryEvent) {
    if (discoveryEnabled && messageSender != null) {
      logger.trace(" <===({}) {} [{}] {}", discoveryEvent.getAddress(),
          discoveryEvent.getMessage().getClass().getSimpleName(), this,
          discoveryEvent.getMessage());
      messageSender.accept(discoveryEvent);
    }
  }

  public void stateChanged(NodeHandler nodeHandler, NodeHandler.State oldState,
      NodeHandler.State newState) {

  }

  public List<NodeHandler> getNodes(Set<String> nodesInUse, int limit) {
    ArrayList<NodeHandler> filtered = new ArrayList<>();
    synchronized (this) {
      for (NodeHandler handler : nodeHandlerMap.values()) {
         if(handler.getState() == NodeHandler.State.Active && !nodesInUse.contains(handler.getNode().getHexId())){
           filtered.add(handler);
         }
      }
    }
    logger.info("filtered size = {} nodeHandlerMap size =  {}", filtered.size(), nodeHandlerMap.size());
    filtered.sort((o1, o2) -> o2.getNodeStatistics().getEthTotalDifficulty().compareTo(
        o1.getNodeStatistics().getEthTotalDifficulty()));
    return CollectionUtils.truncate(filtered, limit);
  }

  private synchronized void processListeners() {
    for (ListenerHandler handler : listeners.values()) {
      try {
        handler.checkAll();
      } catch (Exception e) {
        logger.error("Exception processing listener: " + handler, e);
      }
    }
  }

  /**
   * Add a listener which is notified when the node statistics starts or stops meeting the criteria
   * specified by [filter] param.
   */
  public synchronized void addDiscoverListener(DiscoverListener listener,
      Predicate<NodeStatistics> filter) {
    listeners.put(listener, new ListenerHandler(listener, filter));
  }

  public synchronized void removeDiscoverListener(DiscoverListener listener) {
    listeners.remove(listener);
  }

  public synchronized String dumpAllStatistics() {
    List<NodeHandler> l = new ArrayList<>(nodeHandlerMap.values());
    l.sort((o1, o2) -> -(o1.getNodeStatistics().getReputation() - o2.getNodeStatistics()
        .getReputation()));

    StringBuilder sb = new StringBuilder();
    int zeroReputCount = 0;
    for (NodeHandler nodeHandler : l) {
      if (nodeHandler.getNodeStatistics().getReputation() > 0) {
        sb.append(nodeHandler).append("\t").append(nodeHandler.getNodeStatistics()).append("\n");
      } else {
        zeroReputCount++;
      }
    }
    sb.append("0 reputation: ").append(zeroReputCount).append(" nodes.\n");
    return sb.toString();
  }

  /**
   * @return home node if config defines it as public, otherwise null
   */
  public Node getPublicHomeNode() {
    Args args = Args.getInstance();
    if (args.isNodeDiscoveryPublicHomeNode()) {
      return homeNode;
    }
    return null;
  }

  public void close() {
    try {
      //peerConnectionManager.close();
      nodeManagerTasksTimer.cancel();
      pongTimer.shutdownNow();
      logStatsTimer.cancel();
    } catch (Exception e) {
      logger.warn("close failed.", e);
    }
  }

  private class ListenerHandler {

    Map<NodeHandler, Object> discoveredNodes = new IdentityHashMap<>();
    DiscoverListener listener;
    Predicate<NodeStatistics> filter;

    ListenerHandler(DiscoverListener listener, Predicate<NodeStatistics> filter) {
      this.listener = listener;
      this.filter = filter;
    }

    void checkAll() {
      for (NodeHandler handler : nodeHandlerMap.values()) {
        boolean has = discoveredNodes.containsKey(handler);
        boolean test = filter.test(handler.getNodeStatistics());
        if (!has && test) {
          listener.nodeAppeared(handler);
          discoveredNodes.put(handler, null);
        } else if (has && !test) {
          listener.nodeDisappeared(handler);
          discoveredNodes.remove(handler);
        }
      }
    }
  }

  public static void main(String[] args) {
    try {
      logger.info(InetAddress.getLocalHost().toString());
      ServerSocket ss = new ServerSocket(7080);
      DatagramSocket socket = new DatagramSocket(7080);
      logger.info(ss.getInetAddress().toString());
      logger.info(ss.getLocalSocketAddress().toString());
      logger.info(ss.getLocalPort() + "");


    } catch (Exception e) {
      logger.info("aaa", e);
    }
    System.out.println(111);
  }
}
