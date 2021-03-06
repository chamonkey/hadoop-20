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
package org.apache.hadoop.hdfs.server.datanode;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hdfs.AvatarZooKeeperClient;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.AvatarProtocol;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.server.namenode.AvatarNode;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.protocol.BlockReport;
import org.apache.hadoop.hdfs.server.protocol.DatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.InterDatanodeProtocol;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.DisallowedDatanodeException;
import org.apache.hadoop.hdfs.server.protocol.UpgradeCommand;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.hdfs.util.InjectionHandler;
import org.apache.hadoop.hdfs.protocol.UnregisteredDatanodeException;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.util.StringUtils;
import org.apache.zookeeper.data.Stat;

/**
 * This is an implementation of the AvatarDataNode, a wrapper
 * for a regular datanode that works with AvatarNode.
 * 
 * The AvatarDataNode is needed to make a vanilla DataNode send
 * block reports to Primary and standby namenodes. The AvatarDataNode
 * does not know which one of the namenodes is primary and which is
 * secondary.
 *
 * Typically, an adminstrator will have to specify the pair of
 * AvatarNodes via fs1.default.name and fs2.default.name
 *
 */

public class AvatarDataNode extends DataNode {

  static {
    Configuration.addDefaultResource("avatar-default.xml");
    Configuration.addDefaultResource("avatar-site.xml");
  }
  public static final Log LOG = LogFactory.getLog(AvatarDataNode.class.getName());

  public AvatarDataNode(Configuration conf, AbstractList<File> dataDirs, 
                        String dnThreadName) throws IOException {
    super(conf, dataDirs);

    AvatarDataNode.dnThreadName = dnThreadName;
  }


  private static List<InetSocketAddress> getDatanodeProtocolAddresses(
      Configuration conf, Collection<String> serviceIds) throws IOException {
    // Use default address as fall back
    String defaultAddress;
    try {
      defaultAddress = conf.get(FileSystem.FS_DEFAULT_NAME_KEY);
      if (defaultAddress != null) {
        Configuration newConf = new Configuration(conf);
        newConf.set(FileSystem.FS_DEFAULT_NAME_KEY, defaultAddress);
        defaultAddress = NameNode.getHostPortString(NameNode.getAddress(newConf));
      }
    } catch (IllegalArgumentException e) {
      defaultAddress = null;
    }
    
    List<InetSocketAddress> addressList = DFSUtil.getAddresses(conf,
        serviceIds, defaultAddress,
        NameNode.DATANODE_PROTOCOL_ADDRESS,
        FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY);
    if (addressList == null) {
      throw new IOException("Incorrect configuration: namenode address "
          + FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY
          + " is not configured.");
    }
    return addressList;
  }
  

  @Override
  void startDataNode(Configuration conf, 
                     AbstractList<File> dataDirs
                     ) throws IOException {
    initGlobalSetting(conf, dataDirs);
    
    Collection<String> serviceIds = DFSUtil.getNameServiceIds(conf);
    List<InetSocketAddress> defaultNameAddrs =
      AvatarDataNode.getDatanodeProtocolAddresses(conf, serviceIds);
    List<InetSocketAddress> nameAddrs0 = 
        DFSUtil.getRPCAddresses("0", conf, serviceIds,
           NameNode.DATANODE_PROTOCOL_ADDRESS, FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY);
    List<InetSocketAddress> nameAddrs1 = 
        DFSUtil.getRPCAddresses("1", conf, serviceIds,
           NameNode.DATANODE_PROTOCOL_ADDRESS, FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY);
    List<InetSocketAddress> avatarAddrs0 =
      AvatarDataNode.getAvatarNodeAddresses("0", conf, serviceIds);
    List<InetSocketAddress> avatarAddrs1 =
      AvatarDataNode.getAvatarNodeAddresses("1", conf, serviceIds);

    namespaceManager = new AvatarNamespaceManager(nameAddrs0, nameAddrs1,
        avatarAddrs0, avatarAddrs1, defaultNameAddrs, 
        DFSUtil.getNameServiceIds(conf));

    initDataSetAndScanner(conf, dataDirs, nameAddrs0.size());
  }
  
  @Override
  protected void notifyNamenodeReceivedBlock(int namespaceId, Block block,
      String delHint) throws IOException {
    if (block == null) {
      throw new IllegalArgumentException("Block is null");
    }   
    ((AvatarNamespaceManager)namespaceManager).notifyNamenodeReceivedBlock(
        namespaceId, block, delHint);
  }

  @Override
  protected void notifyNamenodeDeletedBlock(int namespaceId, Block block)
      throws IOException {
    if (block == null) {
      throw new IllegalArgumentException("Block is null");
    }   
    ((AvatarNamespaceManager)namespaceManager).notifyNamenodeDeletedBlock(namespaceId, block); 
  }

  /** TODO: will add more details to this later on
   * Manages OfferService objects for the data node namespaces.
   * Each namespace has two OfferServices, one for pirmary and one for standby.
   * Creation, removal, starting, stopping, shutdown on OfferService
   * objects must be done via APIs in this class.
   */
  class AvatarNamespaceManager extends NamespaceManager {
    private final Object refreshNamenodesLock = new Object();
    AvatarNamespaceManager(
        List<InetSocketAddress> nameAddrs0,
        List<InetSocketAddress> nameAddrs1,
        List<InetSocketAddress> avatarAddrs0,
        List<InetSocketAddress> avatarAddrs1,
        List<InetSocketAddress> defaultAddrs,
        Collection<String> nameserviceIds) throws IOException {
      Iterator<String> it = nameserviceIds.iterator();
       for ( int i = 0; i<nameAddrs0.size(); i++) {
         InetSocketAddress nameAddr0 = nameAddrs0.get(i);
         String nameserviceId = it.hasNext()? it.next(): null;
         nameNodeThreads.put(nameAddr0, 
                             new ServicePair(nameAddr0, nameAddrs1.get(i),
                                 avatarAddrs0.get(i), avatarAddrs1.get(i),
                                 defaultAddrs.get(i), nameserviceId));
       }
      
    }
    
    /**
     * Notify both namenode(s) that we have received a block
     */
    protected void notifyNamenodeReceivedBlock(int namespaceId, Block block,
        String delHint) throws IOException {
      NamespaceService servicePair = get(namespaceId);
      if (servicePair == null) {
        throw new IOException("Cannot locate OfferService thread for namespace="
            + namespaceId);
      }
      servicePair.notifyNamenodeReceivedBlock(block, delHint);
    }

    /**
     * Notify both namenode(s) that we have deleted a block
     */
    protected void notifyNamenodeDeletedBlock(int namespaceId, Block block)
    throws IOException {
      NamespaceService servicePair = this.get(namespaceId);
      if (servicePair == null) {
        throw new IOException("Cannot locate OfferService thread for namespace="
            + namespaceId);
      }
      servicePair.notifyNamenodeDeletedBlock(block);
    }
    
    void refreshNamenodes(
        List<InetSocketAddress> nameAddrs0,
        List<InetSocketAddress> nameAddrs1,
        List<InetSocketAddress> avatarAddrs0,
        List<InetSocketAddress> avatarAddrs1,
        List<InetSocketAddress> defaultAddrs,
        Collection<String> nameserviceIds)
        throws IOException, InterruptedException{
      List<Integer> toStart = new ArrayList<Integer>();
      List<String> toStartNameserviceIds = new ArrayList<String>();
      List<NamespaceService> toStop = new ArrayList<NamespaceService>();
      synchronized (refreshNamenodesLock) {
        synchronized (this) {
          for (InetSocketAddress nnAddr : nameNodeThreads.keySet()) {
            if (!nameAddrs0.contains(nnAddr)){
              toStop.add(nameNodeThreads.get(nnAddr));
            }
          }
          Iterator<String> it = nameserviceIds.iterator();
          for (int i = 0; i < nameAddrs0.size(); i++) {
            String nameserviceId = it.hasNext()? it.next() : null;
            if (!nameNodeThreads.containsKey(nameAddrs0.get(i))) {
              toStart.add(i);
              toStartNameserviceIds.add(nameserviceId);
            }
          }
          it = toStartNameserviceIds.iterator();
          for (Integer i : toStart) {
            InetSocketAddress nameAddr0 = nameAddrs0.get(i);
            nameNodeThreads.put(nameAddr0, 
                new ServicePair(nameAddr0, nameAddrs1.get(i),
                    avatarAddrs0.get(i), avatarAddrs1.get(i),
                    defaultAddrs.get(i), it.next()));
          }
          for (NamespaceService nsos : toStop) {
            remove(nsos);
          }
        }
        for (NamespaceService nsos : toStop) {
          nsos.stop();
        }
        for (NamespaceService nsos : toStop) {
          nsos.join();
        }
        startAll();
      }
    }
  }

  public class ServicePair extends NamespaceService {
    String defaultAddr;
    InetSocketAddress nameAddr1;
    InetSocketAddress nameAddr2;
    DatanodeProtocol namenode1;
    DatanodeProtocol namenode2;
    AvatarProtocol avatarnode1;
    AvatarProtocol avatarnode2;
    InetSocketAddress avatarAddr1;
    InetSocketAddress avatarAddr2;
    boolean doneRegister1 = false;    // not yet registered with namenode1
    boolean doneRegister2 = false;    // not yet registered with namenode2
    OfferService offerService1;
    OfferService offerService2;
    volatile OfferService primaryOfferService = null;
    Thread of1;
    Thread of2;
    int namespaceId;
    String nameserviceId;
    Thread spThread;
    AvatarZooKeeperClient zkClient;
    private NamespaceInfo nsInfo;
    DatanodeRegistration nsRegistration;
    private UpgradeManagerDatanode upgradeManager;
    private volatile boolean initialized = false;
    private volatile boolean shouldServiceRun = true;
    volatile long lastBeingAlive = now();

    private ServicePair(InetSocketAddress nameAddr1, InetSocketAddress nameAddr2,
        InetSocketAddress avatarAddr1, InetSocketAddress avatarAddr2,
        InetSocketAddress defaultAddr, String nameserviceId) {
      this.nameAddr1 = nameAddr1;
      this.nameAddr2 = nameAddr2;
      this.avatarAddr1 = avatarAddr1;
      this.avatarAddr2 = avatarAddr2;
      this.defaultAddr = defaultAddr.getHostName() + ":" + defaultAddr.getPort();
      this.nameserviceId = nameserviceId;
      zkClient = new AvatarZooKeeperClient(getConf(), null);
      this.nsRegistration = new DatanodeRegistration(getMachineName());
    }
    
    private void setNamespaceInfo(NamespaceInfo nsinfo) {
      this.nsInfo = nsinfo;
      this.namespaceId = nsinfo.getNamespaceID();
      namespaceManager.addNamespace(this);
    }

    private void setupNS() throws IOException {
      // handshake with NN
      NamespaceInfo nsInfo;
      nsInfo = handshake(true);
      setNamespaceInfo(nsInfo);
      synchronized(AvatarDataNode.this){
        setupNSStorage();
      }
      
      nsRegistration.setIpcPort(ipcServer.getListenerAddress().getPort());
      nsRegistration.setInfoPort(infoServer.getPort());
    }
    
    private void setupNSStorage() throws IOException {
      Configuration conf = getConf();
      StartupOption startOpt = getStartupOption(conf);
      assert startOpt != null : "Startup option must be set.";

      boolean simulatedFSDataset = 
        conf.getBoolean("dfs.datanode.simulateddatastorage", false);
      
      if (simulatedFSDataset) {
        nsRegistration.setStorageID(storage.getStorageID()); //same as DN
        nsRegistration.storageInfo.layoutVersion = FSConstants.LAYOUT_VERSION;
        nsRegistration.storageInfo.namespaceID = nsInfo.namespaceID;
      } else {
        // read storage info, lock data dirs and transition fs state if necessary      
        // first do it at the top level dataDirs
        // This is done only once when among all namespaces
        storage.recoverTransitionRead(AvatarDataNode.this, nsInfo, dataDirs, startOpt);
        // Then do it for this namespace's directory
        storage.recoverTransitionRead(AvatarDataNode.this, nsInfo.namespaceID,
            nsInfo, dataDirs, startOpt, nameserviceId);
        
        LOG.info("setting up storage: namespaceId="
            + namespaceId + ";lv=" + storage.layoutVersion + ";nsInfo="
            + nsInfo);

        nsRegistration.setStorageInfo(
            storage.getNStorage(nsInfo.namespaceID), storage.getStorageID());
        data.initialize(storage);
        
      }
      data.addNamespace(namespaceId, storage.getNameSpaceDataDir(namespaceId), conf);
      if (blockScanner != null) {
        blockScanner.start();
        blockScanner.addNamespace(namespaceId);
      }
    }
    
    @Override
    public UpgradeManagerDatanode getUpgradeManager() {
      synchronized (AvatarDataNode.this) {
      if(upgradeManager == null)
        upgradeManager = 
          new UpgradeManagerDatanode(AvatarDataNode.this, namespaceId);
      }
      return upgradeManager;
    }
    
    public void processUpgradeCommand(UpgradeCommand comm)
    throws IOException {
      assert upgradeManager != null : "DataNode.upgradeManager is null.";
      upgradeManager.processUpgradeCommand(comm);
    }

    /**
     * Start distributed upgrade if it should be initiated by the data-node.
     */
    private void startDistributedUpgradeIfNeeded() throws IOException {
      UpgradeManagerDatanode um = getUpgradeManager();

      if(!um.getUpgradeState())
        return;
      um.setUpgradeState(false, um.getUpgradeVersion());
      um.startUpgrade();
      return;
    }

    public void start() {
      if ((spThread != null) && (spThread.isAlive())) {
        //Thread is started already
        return;
      }
      spThread = new Thread(this, dnThreadName + " for namespace " + namespaceId);
      spThread.setDaemon(true);
      spThread.start();

    }
    
    public void stop() {
      stopServices();
      if (spThread != null) {
        spThread.interrupt();
      }
    }
    
    private void initProxy1() throws IOException {
      synchronized (avatarAddr1) {
        if (namenode1 == null) {
          namenode1 = (DatanodeProtocol) RPC.getProxy(DatanodeProtocol.class,
              DatanodeProtocol.versionID, nameAddr1, getConf());
        }

        if (avatarnode1 == null) {
          avatarnode1 = (AvatarProtocol) RPC.getProxy(AvatarProtocol.class,
              AvatarProtocol.versionID, avatarAddr1, getConf());
        }
      }
    }

    private void initProxy2() throws IOException {
      synchronized (avatarAddr2) {
        if (namenode2 == null) {
          namenode2 = (DatanodeProtocol) RPC.getProxy(DatanodeProtocol.class,
              DatanodeProtocol.versionID, nameAddr2, getConf());
        }
        if (avatarnode2 == null) {
          avatarnode2 = (AvatarProtocol) RPC.getProxy(AvatarProtocol.class,
              AvatarProtocol.versionID, avatarAddr2, getConf());
        }
      }
    }

    public void restartService1() throws IOException {
      // Rely on handshake to restart the service.
      synchronized (avatarAddr1) {
        stopService1();
        joinService1();
        doneRegister1 = false;
      }
    }

    void stopService1() {
      RPC.stopProxy(avatarnode1);
      RPC.stopProxy(namenode1);
      avatarnode1 = null;
      namenode1 = null;
      if (offerService1 != null) {
        offerService1.stop();
      }
      if (of1 != null) {
        of1.interrupt();
      }
    }

    void stopService2() {
      RPC.stopProxy(avatarnode2);
      RPC.stopProxy(namenode2);
      avatarnode2 = null;
      namenode2 = null;
      if (offerService2 != null) {
        offerService2.stop();
      }
      if (of2 != null) {
        of2.interrupt();
      }
    }

    private void joinService1() {
      if (of1 != null) {
        try {
          of1.join();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void joinService2() {
      if (of2 != null) {
        try {
          of2.join();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }

    public void restartService2() throws IOException {
      // Rely on handshake to restart the service.
      synchronized (avatarAddr2) {
        stopService2();
        joinService2();
        doneRegister2 = false;
      }
    }

    /** stop two offer services */
    private void stopServices() {
      this.shouldServiceRun = false;
      LOG.info("stop services " + this.nameserviceId);
      stopService1();
      stopService2();
      if (zkClient != null) {
        try {
          zkClient.shutdown();
        } catch (InterruptedException ie) {
          LOG.warn("Zk shutdown is interrupted: ", ie);
        }
      }
    }
    
    public void join() {
      joinServices();
      if (spThread != null) {
        try {
          spThread.join();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
        spThread = null;
      }
    }
    
    /** Join two offer services */
    private void joinServices() {
      joinService1();
      joinService2();
    }
    
    public void cleanUp() {
      if(upgradeManager != null)
        upgradeManager.shutdownUpgrade();
      
      namespaceManager.remove(this);
      shouldServiceRun = false;
      try {
        RPC.stopProxy(namenode1);
      } catch (Exception e){
        LOG.warn("Exception stop the namenode RPC threads", e);
      }
      try {
        RPC.stopProxy(namenode2);
      } catch (Exception e){
        LOG.warn("Exception stop the namenode RPC threads", e);
      }
      if (blockScanner != null) {
        blockScanner.removeNamespace(this.getNamespaceId());
      }
      if (data != null) { 
        data.removeNamespace(this.getNamespaceId());
      }
      if (storage != null) {
        storage.removeNamespaceStorage(this.getNamespaceId());
      }
    }
    
    public void shutdown() {
      stop();
      join();
    }
    
  // connect to both name node if possible. 
  // If doWait is true, then return only when at least one handshake is
  // successful.
  //
  private NamespaceInfo handshake(boolean startup) throws IOException {
    NamespaceInfo nsInfo = null;
    boolean firstIsPrimary = false;
    // When true indicates ZK is null and there is no primary. This is to
    // enable datanode startups during failover. The assumption is that the
    // layout version of the Standby and Primary would be consistent when
    // we failover and hence we can speak to any one of the nodes to find out
    // the NamespaceInfo.
    boolean noPrimary = false;
    boolean needResolveNNAddr1 = false;
    long lastResolveTime1 = 0;
    boolean needResolveNNAddr2 = false;
    long lastResolveTime2 = 0;
    long DNS_RESOLVE_MIN_INTERVAL = 120 * 1000;
    
    do {
      if (startup) {
        // The startup option is used when the datanode is first created
        // We only need to connect to the primary at this point and as soon
        // as possible. So figure out who the primary is from the ZK
        Stat stat = new Stat();
        try {
          String primaryAddress =
            zkClient.getPrimaryAvatarAddress(defaultAddr, stat, false);
            noPrimary = (primaryAddress == null);
          String firstNNAddress = nameAddr1.getHostName() + ":" +
            nameAddr1.getPort();
          firstIsPrimary = firstNNAddress.equalsIgnoreCase(primaryAddress);
        } catch (Exception ex) {
          LOG.error("Could not get the primary address from ZooKeeper", ex);
        }
      }
      try {
          if ((firstIsPrimary && startup) || !startup || noPrimary) {          
            // only try to connect to the first NN if it is not the
            // startup connection or if it is primary on startup
            // This way if it is standby we are not wasting datanode startup
            // time
            if (needResolveNNAddr1
                && System.currentTimeMillis() - lastResolveTime1 > DNS_RESOLVE_MIN_INTERVAL) {
              // Try to resolve DNS address again
              InetSocketAddress newAddr;
              boolean addressChanged = false;
              newAddr = NetUtils.resolveAddress(nameAddr1);
              if (newAddr != null) {
                nameAddr1 = newAddr;
                addressChanged = true;
              }
              newAddr = NetUtils.resolveAddress(avatarAddr1);
              if (newAddr != null) {
                avatarAddr1 = newAddr;
                addressChanged = true;
              }
              lastResolveTime1 = System.currentTimeMillis();
              needResolveNNAddr1 = false;
              
              if (addressChanged) {
                stopService1();
              }
            }
            initProxy1();

            if (startup) {
              nsInfo = handshake(namenode1, nameAddr1);
            }
        }
      } catch(ConnectException se) {  // namenode has not been started
        LOG.info("Server at " + nameAddr1 + " not available yet, Zzzzz...");
        needResolveNNAddr1 = true;
      } catch (NoRouteToHostException nrhe) {
        LOG.info("NoRouteToHostException connecting to server. " + nameAddr1,
            nrhe);
        needResolveNNAddr1 = true;
      } catch (PortUnreachableException pue) {
        LOG.info("PortUnreachableException connecting to server. "
            + nameAddr1, pue);
        needResolveNNAddr1 = true;
       } catch (UnknownHostException uhe) {
         LOG.info("UnknownHostException connecting to server. " + nameAddr1,
             uhe);
        needResolveNNAddr1 = true;
      } catch(SocketTimeoutException te) {  // namenode is busy
        LOG.info("Problem connecting to server timeout. " + nameAddr1);
      } catch (IOException ioe) {
        LOG.info("Problem connecting to server. " + nameAddr1, ioe);
      }
      try {
        if ((!firstIsPrimary && startup) || !startup || noPrimary) {
            if (needResolveNNAddr2
                && System.currentTimeMillis() - lastResolveTime1 > DNS_RESOLVE_MIN_INTERVAL) {
            // Try to resolve DNS address again
            InetSocketAddress newAddr;
            boolean addressChanged = false;
            newAddr = NetUtils.resolveAddress(nameAddr2);
            if (newAddr != null) {
              nameAddr2 = newAddr;
              addressChanged = true;
            }
            newAddr = NetUtils.resolveAddress(avatarAddr2);
            if (newAddr != null) {
              avatarAddr2 = newAddr;
              addressChanged = true;
            }
            lastResolveTime2 = System.currentTimeMillis();
            needResolveNNAddr2 = false;
            
            if (addressChanged) {
              stopService2();
            }

          }

          initProxy2();

          if (startup) {
            NamespaceInfo tempInfo = handshake(namenode2, nameAddr2);
            // During failover both layouts should match.
            if (noPrimary && nsInfo != null && tempInfo.getLayoutVersion()
                 != nsInfo.getLayoutVersion()) {
              throw new IOException("Layout versions don't match on zero, one: "
                  + nsInfo.getLayoutVersion() + ", "
                  + tempInfo.getLayoutVersion());
            }
            nsInfo = tempInfo;
          }
        }
      } catch(ConnectException se) {  // namenode has not been started
        LOG.info("Server at " + nameAddr2 + " not available yet, Zzzzz...");
        needResolveNNAddr2 = true;
      } catch (NoRouteToHostException nrhe) {
        LOG.info("NoRouteToHostException connecting to server. " + nameAddr2,
            nrhe);
        needResolveNNAddr2 = true;
      } catch (PortUnreachableException pue) {
        LOG.info("PortUnreachableException connecting to server. "
            + nameAddr2, pue);
        needResolveNNAddr2 = true;
       } catch (UnknownHostException uhe) {
         LOG.info("UnknownHostException connecting to server. " + nameAddr2,
             uhe);
        needResolveNNAddr2 = true;
      } catch(SocketTimeoutException te) {  // namenode is busy
        LOG.info("Problem connecting to server timeout. " + nameAddr2);
      } catch (RemoteException re) {
        handleRegistrationError(re);
      } catch (IOException ioe) {
        LOG.info("Problem connecting to server. " + nameAddr2, ioe);
      }
    } while (startup && nsInfo == null && shouldServiceRun);
    return nsInfo;
  }

  private NamespaceInfo handshake(DatanodeProtocol node,
                                  InetSocketAddress machine) throws IOException {
    NamespaceInfo nsInfo = new NamespaceInfo();
    while (shouldServiceRun) {
      try {
        nsInfo = node.versionRequest();
        break;
      } catch(SocketTimeoutException e) {  // namenode is busy
        LOG.info("Problem connecting to server: " + machine);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {}
      }
    }
    String errorMsg = null;
    // do not fail on incompatible build version
    if( ! nsInfo.getBuildVersion().equals( Storage.getBuildVersion() )) {
      errorMsg = "Incompatible build versions: namenode BV = " 
        + nsInfo.getBuildVersion() + "; datanode BV = "
        + Storage.getBuildVersion();
      LOG.warn( errorMsg );
    }
    if (FSConstants.LAYOUT_VERSION != nsInfo.getLayoutVersion()) {
      errorMsg = "Data-node and name-node layout versions must be the same."
                  + "Expected: "+ FSConstants.LAYOUT_VERSION + 
                  " actual "+ nsInfo.getLayoutVersion();
      LOG.fatal(errorMsg);
      try {
        node.errorReport(nsRegistration,
                         DatanodeProtocol.NOTIFY, errorMsg );
      } catch( SocketTimeoutException e ) {  // namenode is busy        
        LOG.info("Problem connecting to server: " + machine);
      }
      shutdownDN();
      throw new IOException(errorMsg);
    }
    return nsInfo;
  }

  /**
   * Returns true if we are able to successfully register with namenode
   */
  boolean register(DatanodeProtocol node, InetSocketAddress machine) 
    throws IOException {
    if (nsRegistration.getStorageID().equals("")) {
      setNewStorageID(nsRegistration);
    }

    DatanodeRegistration tmp = new DatanodeRegistration(nsRegistration.getName());
    tmp.setInfoPort(nsRegistration.getInfoPort());
    tmp.setIpcPort(nsRegistration.getIpcPort());
    boolean simulatedFSDataset = 
        conf.getBoolean("dfs.datanode.simulateddatastorage", false);
    if (simulatedFSDataset) {
      tmp.setStorageID(storage.getStorageID()); //same as DN
      tmp.storageInfo.layoutVersion = FSConstants.LAYOUT_VERSION;
      tmp.storageInfo.namespaceID = nsInfo.namespaceID;
    } else {
      tmp.setStorageInfo(storage.getNStorage(namespaceId), storage.getStorageID());
    }

    // reset name to machineName. Mainly for web interface.
    tmp.name = machineName + ":" + nsRegistration.getPort();
    try {
      tmp = node.register(tmp, DataTransferProtocol.DATA_TRANSFER_VERSION);
      // if we successded registering for the first time, then we update
      // the global registration objct
      if (!doneRegister1 && !doneRegister2) {
        nsRegistration = tmp;
      }
    } catch(SocketTimeoutException e) {  // namenode is busy
      LOG.info("Problem connecting to server: " + machine);
      return false;
    }

    assert ("".equals(storage.getStorageID()) 
            && !"".equals(nsRegistration.getStorageID()))
            || storage.getStorageID().equals(nsRegistration.getStorageID()) :
            "New storageID can be assigned only if data-node is not formatted";
    if (storage.getStorageID().equals("")) {
      storage.setStorageID(nsRegistration.getStorageID());
      storage.writeAll();
      LOG.info("New storage id " + nsRegistration.getStorageID()
          + " is assigned to data-node " + nsRegistration.getName());
    }
    if(! storage.getStorageID().equals(nsRegistration.getStorageID())) {
      throw new IOException("Inconsistent storage IDs. Name-node returned "
          + nsRegistration.getStorageID() 
          + ". Expecting " + storage.getStorageID());
    }

    sendBlocksBeingWrittenReport(node, namespaceId, nsRegistration);
    return true;
  }
  
  boolean isPrimaryOfferService(OfferService service) {
    return primaryOfferService == service;
  }
  
  void setPrimaryOfferService(OfferService service) {
    this.primaryOfferService = service;
    if (service != null)
      LOG.info("Primary namenode is set to be " + service.avatarnodeAddress);
    else {
      LOG.info("Failover has happened. Stop accessing commands from " +
      		"either namenode until the new primary is completely in" +
      		"sync with all the datanodes");
    }
  }
  
  private void register1() throws IOException {
    synchronized(avatarAddr1) {
      InjectionHandler.processEvent(InjectionEvent.AVATARDATANODE_BEFORE_START_OFFERSERVICE1);
      if (avatarnode1 != null && namenode1 != null && !doneRegister1 &&
          register(namenode1, nameAddr1)) {
        InjectionHandler.processEvent(InjectionEvent.AVATARDATANODE_START_OFFERSERVICE1);
        doneRegister1 = true;
        offerService1 = new OfferService(AvatarDataNode.this, this,
            namenode1, nameAddr1,
            avatarnode1, avatarAddr1);
        of1 = new Thread(offerService1, "OfferService1 " + nameAddr1);
        of1.start();
      }
    }
  }

  private void register2() throws IOException {
    synchronized(avatarAddr2) {
      if (avatarnode2 != null && namenode2 != null && !doneRegister2 &&
          register(namenode2, nameAddr2)) {
        InjectionHandler.processEvent(InjectionEvent.AVATARDATANODE_START_OFFERSERVICE2);
        doneRegister2 = true;
        offerService2 = new OfferService(AvatarDataNode.this, this,
            namenode2, nameAddr2,
            avatarnode2, avatarAddr2);
        of2 = new Thread(offerService2, "OfferService2 " + nameAddr2);
        of2.start();
      }
    }
  }

  @Override
  public void run() {
    LOG.info(nsRegistration + "In AvatarDataNode.run, data = " + data);

    try {
    // set up namespace
    try {
      setupNS();
    } catch (IOException ioe) {
      // Initial handshake, storage recovery or registration failed
      LOG.fatal(nsRegistration + " initialization failed for namespaceId "
          + namespaceId, ioe);
      return;
    }
    
    while (shouldServiceRun && shouldRun) {
      try {
        // try handshaking with any namenode that we have not yet tried
        handshake(false);

        try {
          register1();
        } finally {
          register2();
        }

        this.initialized = true;
        startDistributedUpgradeIfNeeded();
      } catch (RemoteException re) {
        handleRegistrationError(re);
      } catch (Exception ex) {
        LOG.error("Exception: ", ex);
      }
      if (shouldServiceRun && shouldRun) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ie) {
        }
      }
    }
    } finally {

    LOG.info(nsRegistration + ":Finishing AvatarDataNode in: "+data);
    stopServices();
    joinServices();
    cleanUp();
    }
  }

  /**
   * Notify both namenode(s) that we have received a block
   */
  @Override
  public void notifyNamenodeReceivedBlock(Block block, String delHint) {
    if (offerService1 != null) {
      offerService1.notifyNamenodeReceivedBlock(block, delHint);
    }
    if (offerService2 != null) {
      offerService2.notifyNamenodeReceivedBlock(block, delHint);
    }
  }

  /**
   * Notify both namenode(s) that we have deleted a block
   */
  @Override
  public void notifyNamenodeDeletedBlock(Block block) {
    if (offerService1 != null) {
      offerService1.notifyNamenodeDeletedBlock(block);
    }
    if (offerService2 != null) {
      offerService2.notifyNamenodeDeletedBlock(block);
    }
  }

  /**
   * Update received and retry list, when blocks are deleted
   */
  void removeReceivedBlocks(Block[] list) {
    if (offerService1 != null) {
      offerService1.removeReceivedBlocks(list);
    }
    if (offerService2 != null) {
      offerService2.removeReceivedBlocks(list);
    }
  }

  @Override
  public DatanodeRegistration getNsRegistration() {
    return nsRegistration;
  }

  @Override
  public DatanodeProtocol getDatanodeProtocol() {
    return this.primaryOfferService.namenode;
  }

  @Override
  public InetSocketAddress getNNSocketAddress() {
    return this.nameAddr1;
  }

  @Override
  public int getNamespaceId() {
    return this.namespaceId;
  }
  
  @Override
  public String getNameserviceId() {
    return this.nameserviceId;
  }

  @Override
  public boolean initialized() {
    return initialized;
  }

  @Override
  public boolean isAlive() {
    return shouldServiceRun && spThread.isAlive();
  }

  @Override
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    if (this.offerService1 != null)
      this.offerService1.reportBadBlocks(blocks);
    if (this.offerService2 != null)
      this.offerService2.reportBadBlocks(blocks);
  }

  @Override
  public LocatedBlock syncBlock(Block block, List<BlockRecord> syncList,
      boolean closeFile, List<InterDatanodeProtocol> datanodeProxies,
      long deadline) throws IOException {
    if (offerService1 != null && isPrimaryOfferService(offerService1))
      return offerService1.syncBlock(block, syncList, closeFile,
          datanodeProxies, deadline);
    if (offerService2 != null && isPrimaryOfferService(offerService2))
      return offerService2.syncBlock(block, syncList, closeFile,
          datanodeProxies, deadline);
    return null;
  }
  
  @Override
  public void scheduleBlockReport(long delay) {
    if (this.offerService1 != null)
      this.offerService1.scheduleBlockReport(delay);
    if (this.offerService2 != null)
      this.offerService2.scheduleBlockReport(delay);
  }
  
  // Only use for testing
  public void scheduleBlockReceivedAndDeleted(long delay) {
    if (this.offerService1 != null)
      this.offerService1.scheduleBlockReceivedAndDeleted(delay);
    if (this.offerService2 != null)
      this.offerService2.scheduleBlockReceivedAndDeleted(delay);
  }

  }
 /**
  * Tells the datanode to start the shutdown process.
  */
  public synchronized void shutdownDN() {
    shouldRun = false;
    if (namespaceManager != null) {
      namespaceManager.stopAll();
    }
  }
  
  DataStorage getStorage() {
    return storage;
  }

  private static void printUsage() {
    System.err.println("Usage: java DataNode");
    System.err.println("           [-rollback]");
  }

  /**
   * Parse and verify command line arguments and set configuration parameters.
   *
   * @return false if passed argements are incorrect
   */
  private static boolean parseArguments(String args[],
                                        Configuration conf) {
    int argsLen = (args == null) ? 0 : args.length;
    StartupOption startOpt = StartupOption.REGULAR;
    for(int i=0; i < argsLen; i++) {
      String cmd = args[i];
      if ("-r".equalsIgnoreCase(cmd) || "--rack".equalsIgnoreCase(cmd)) {
        LOG.error("-r, --rack arguments are not supported anymore. RackID " +
            "resolution is handled by the NameNode.");
        System.exit(-1);
      } else if ("-rollback".equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.ROLLBACK;
      } else if ("-regular".equalsIgnoreCase(cmd)) {
        startOpt = StartupOption.REGULAR;
      } else
        return false;
    }
    setStartupOption(conf, startOpt);
    return true;
  }

  private static void setStartupOption(Configuration conf, StartupOption opt) {
    conf.set("dfs.datanode.startup", opt.toString());
  }

  /**
   * Returns the IP address of the namenode
   */
  static InetSocketAddress getNameNodeAddress(Configuration conf,
                                                      String cname, String rpcKey, String cname2) {
    String fs = conf.get(cname);
    String fs1 = conf.get(rpcKey);
    String fs2 = conf.get(cname2);
    Configuration newconf = new Configuration(conf);
    newconf.set("fs.default.name", fs);
    if (fs1 != null) {
      newconf.set(DFS_NAMENODE_RPC_ADDRESS_KEY, fs1);
    }
    if (fs2 != null) {
      newconf.set("dfs.namenode.dn-address", fs2);
    }
    return DataNode.getNameNodeAddress(newconf);
  }

  @Override
  public InetSocketAddress getNameNodeAddr() {
    return NameNode.getAddress(getConf());
  }

  /**
   * Returns the IP:port address of the avatar node
   */
  private static InetSocketAddress getAvatarNodeAddress(Configuration conf,
                                                        String cname) {
    String fs = conf.get(cname);
    Configuration newconf = new Configuration(conf);
    newconf.set("fs.default.name", fs);
    return AvatarNode.getAddress(newconf);
  }

  /**
   * Returns the IP:port address of the avatar node
   */
  public static List<InetSocketAddress> getAvatarNodeAddresses(String suffix,
      Configuration conf, Collection<String> serviceIds) throws IOException{
    List<InetSocketAddress> namenodeAddresses = DFSUtil.getRPCAddresses(suffix,
        conf, serviceIds, FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY);
    List<InetSocketAddress> avatarnodeAddresses = 
      new ArrayList<InetSocketAddress>(namenodeAddresses.size());
    for (InetSocketAddress namenodeAddress : namenodeAddresses) {
      avatarnodeAddresses.add(
          new InetSocketAddress(namenodeAddress.getHostName(),conf.getInt(
              "dfs.avatarnode.port", namenodeAddress.getPort() + 1)));
    }
    return avatarnodeAddresses;
  }

  public static AvatarDataNode makeInstance(String[] dataDirs, Configuration conf)
    throws IOException {
    ArrayList<File> dirs = new ArrayList<File>();
    for (int i = 0; i < dataDirs.length; i++) {
      File data = new File(dataDirs[i]);
      try {
        DiskChecker.checkDir(data);
        dirs.add(data);
      } catch(DiskErrorException e) {
        LOG.warn("Invalid directory in dfs.data.dir: " + e.getMessage());
      }
    }
    if (dirs.size() > 0) {
      String dnThreadName = "AvatarDataNode: [" +
        StringUtils.arrayToString(dataDirs) + "]";
      return new AvatarDataNode(conf, dirs, dnThreadName);
    }
    LOG.error("All directories in dfs.data.dir are invalid.");
    return null;
  }

  /** Instantiate a single datanode object. This must be run by invoking
   *  {@link DataNode#runDatanodeDaemon(DataNode)} subsequently. 
   */
  public static AvatarDataNode instantiateDataNode(String args[],
                                      Configuration conf) throws IOException {
    if (conf == null)
      conf = new Configuration();
    if (!parseArguments(args, conf)) {
      printUsage();
      return null;
    }
    if (conf.get("dfs.network.script") != null) {
      LOG.error("This configuration for rack identification is not supported" +
          " anymore. RackID resolution is handled by the NameNode.");
      System.exit(-1);
    }
    String[] dataDirs = getListOfDataDirs(conf);
    return makeInstance(dataDirs, conf);
  }

  public static AvatarDataNode createDataNode(String args[],
                                 Configuration conf) throws IOException {
    AvatarDataNode dn = instantiateDataNode(args, conf);
    dn.runDatanodeDaemon();
    return dn;
  }
  
  @Override
  public void refreshNamenodes(Configuration conf) throws IOException {
    LOG.info("refresh namenodes");
    try {
      Collection<String> serviceIds = DFSUtil.getNameServiceIds(conf);
      List<InetSocketAddress> defaultNameAddrs = 
          AvatarDataNode.getDatanodeProtocolAddresses(conf, serviceIds);
      List<InetSocketAddress> nameAddrs0 = 
          DFSUtil.getRPCAddresses("0", conf, serviceIds,
              NameNode.DATANODE_PROTOCOL_ADDRESS,
              FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY);
      List<InetSocketAddress> nameAddrs1 =
          DFSUtil.getRPCAddresses("1", conf, serviceIds,
              NameNode.DATANODE_PROTOCOL_ADDRESS, 
              FSConstants.DFS_NAMENODE_RPC_ADDRESS_KEY);
      List<InetSocketAddress> avatarAddrs0 =
          AvatarDataNode.getAvatarNodeAddresses("0", conf, serviceIds);
      List<InetSocketAddress> avatarAddrs1 =
          AvatarDataNode.getAvatarNodeAddresses("1", conf, serviceIds);
      ((AvatarNamespaceManager)namespaceManager).refreshNamenodes(
          nameAddrs0, nameAddrs1,
          avatarAddrs0, avatarAddrs1, 
          defaultNameAddrs, serviceIds);
    } catch (InterruptedException e) {
      throw new IOException(e.getCause());
    }
  }

  void handleRegistrationError(RemoteException re) {
    // If either the primary or standby NN throws these exceptions, this
    // datanode will exit. I think this is the right behaviour because
    // the excludes list on both namenode better be the same.
    String reClass = re.getClassName(); 
    if (UnregisteredDatanodeException.class.getName().equals(reClass) ||
        DisallowedDatanodeException.class.getName().equals(reClass) ||
        IncorrectVersionException.class.getName().equals(reClass)) {
      LOG.warn("DataNode is shutting down: ", re);
      shutdownDN();
    } else {
      LOG.warn(re);
    }
  }
    
  public static void main(String argv[]) {
    try {
      StringUtils.startupShutdownMessage(AvatarDataNode.class, argv, LOG);
      AvatarDataNode avatarnode = createDataNode(argv, null);
      if (avatarnode != null) {
        avatarnode.waitAndShutdown();
      }
    } catch (Throwable e) {
      LOG.error(StringUtils.stringifyException(e));
      System.exit(-1);
    }
  }


}
