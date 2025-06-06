/*
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
package org.apache.hadoop.hbase.fs;

import static org.apache.hadoop.hbase.util.LocatedBlockHelper.getLocatedBlockLocations;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.util.ReflectionUtils;
import org.apache.hadoop.hbase.wal.AbstractFSWALProvider;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.BlockStoragePolicy;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.HdfsFileStatus;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockStoragePolicySuite;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.util.Progressable;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An encapsulation for the FileSystem object that hbase uses to access data. This class allows the
 * flexibility of using separate filesystem objects for reading and writing hfiles and wals.
 */
@InterfaceAudience.Private
public class HFileSystem extends FilterFileSystem {
  public static final Logger LOG = LoggerFactory.getLogger(HFileSystem.class);

  private final FileSystem noChecksumFs; // read hfile data from storage
  private final boolean useHBaseChecksum;
  private static volatile byte unspecifiedStoragePolicyId = Byte.MIN_VALUE;

  /**
   * Create a FileSystem object for HBase regionservers.
   * @param conf             The configuration to be used for the filesystem
   * @param useHBaseChecksum if true, then use checksum verfication in hbase, otherwise delegate
   *                         checksum verification to the FileSystem.
   */
  public HFileSystem(Configuration conf, boolean useHBaseChecksum) throws IOException {

    // Create the default filesystem with checksum verification switched on.
    // By default, any operation to this FilterFileSystem occurs on
    // the underlying filesystem that has checksums switched on.
    // This FS#get(URI, conf) clearly indicates in the javadoc that if the FS is
    // not created it will initialize the FS and return that created FS. If it is
    // already created it will just return the FS that was already created.
    // We take pains to funnel all of our FileSystem instantiation through this call to ensure
    // we never need to call FS.initialize ourself so that we do not have to track any state to
    // avoid calling initialize more than once.
    this.fs = FileSystem.get(getDefaultUri(conf), conf);
    this.useHBaseChecksum = useHBaseChecksum;

    // disable checksum verification for local fileSystem, see HBASE-11218
    if (fs instanceof LocalFileSystem) {
      fs.setWriteChecksum(false);
      fs.setVerifyChecksum(false);
    }

    addLocationsOrderInterceptor(conf);

    // If hbase checksum verification is switched on, then create a new
    // filesystem object that has cksum verification turned off.
    // We will avoid verifying checksums in the fs client, instead do it
    // inside of hbase.
    // If this is the local file system hadoop has a bug where seeks
    // do not go to the correct location if setVerifyChecksum(false) is called.
    // This manifests itself in that incorrect data is read and HFileBlocks won't be able to read
    // their header magic numbers. See HBASE-5885
    if (useHBaseChecksum && !(fs instanceof LocalFileSystem)) {
      conf = new Configuration(conf);
      conf.setBoolean("dfs.client.read.shortcircuit.skip.checksum", true);
      this.noChecksumFs = maybeWrapFileSystem(newInstanceFileSystem(conf), conf);
      this.noChecksumFs.setVerifyChecksum(false);
    } else {
      this.noChecksumFs = maybeWrapFileSystem(fs, conf);
    }

    this.fs = maybeWrapFileSystem(this.fs, conf);
  }

  /**
   * Wrap a FileSystem object within a HFileSystem. The noChecksumFs and writefs are both set to be
   * the same specified fs. Do not verify hbase-checksums while reading data from filesystem.
   * @param fs Set the noChecksumFs and writeFs to this specified filesystem.
   */
  public HFileSystem(FileSystem fs) {
    this.fs = fs;
    this.noChecksumFs = fs;
    this.useHBaseChecksum = false;
  }

  /**
   * Returns the filesystem that is specially setup for doing reads from storage. This object avoids
   * doing checksum verifications for reads.
   * @return The FileSystem object that can be used to read data from files.
   */
  public FileSystem getNoChecksumFs() {
    return noChecksumFs;
  }

  /**
   * Returns the underlying filesystem
   * @return The underlying FileSystem for this FilterFileSystem object.
   */
  public FileSystem getBackingFs() throws IOException {
    return fs;
  }

  /**
   * Set the source path (directory/file) to the specified storage policy.
   * @param path       The source path (directory/file).
   * @param policyName The name of the storage policy: 'HOT', 'COLD', etc. See see hadoop 2.6+
   *                   org.apache.hadoop.hdfs.protocol.HdfsConstants for possible list e.g 'COLD',
   *                   'WARM', 'HOT', 'ONE_SSD', 'ALL_SSD', 'LAZY_PERSIST'.
   */
  public void setStoragePolicy(Path path, String policyName) {
    CommonFSUtils.setStoragePolicy(this.fs, path, policyName);
  }

  /**
   * Get the storage policy of the source path (directory/file).
   * @param path The source path (directory/file).
   * @return Storage policy name, or {@code null} if not using {@link DistributedFileSystem} or
   *         exception thrown when trying to get policy
   */
  @Nullable
  public String getStoragePolicyName(Path path) {
    try {
      Object blockStoragePolicySpi =
        ReflectionUtils.invokeMethod(this.fs, "getStoragePolicy", path);
      return (String) ReflectionUtils.invokeMethod(blockStoragePolicySpi, "getName");
    } catch (Exception e) {
      // Maybe fail because of using old HDFS version, try the old way
      if (LOG.isTraceEnabled()) {
        LOG.trace("Failed to get policy directly", e);
      }
      return getStoragePolicyForOldHDFSVersion(path);
    }
  }

  /**
   * Before Hadoop 2.8.0, there's no getStoragePolicy method for FileSystem interface, and we need
   * to keep compatible with it. See HADOOP-12161 for more details.
   * @param path Path to get storage policy against
   * @return the storage policy name
   */
  private String getStoragePolicyForOldHDFSVersion(Path path) {
    try {
      if (this.fs instanceof DistributedFileSystem) {
        DistributedFileSystem dfs = (DistributedFileSystem) this.fs;
        HdfsFileStatus status = dfs.getClient().getFileInfo(path.toUri().getPath());
        if (null != status) {
          if (unspecifiedStoragePolicyId < 0) {
            // Get the unspecified id field through reflection to avoid compilation error.
            // In later version BlockStoragePolicySuite#ID_UNSPECIFIED is moved to
            // HdfsConstants#BLOCK_STORAGE_POLICY_ID_UNSPECIFIED
            Field idUnspecified = BlockStoragePolicySuite.class.getField("ID_UNSPECIFIED");
            unspecifiedStoragePolicyId = idUnspecified.getByte(BlockStoragePolicySuite.class);
          }
          byte storagePolicyId = status.getStoragePolicy();
          if (storagePolicyId != unspecifiedStoragePolicyId) {
            BlockStoragePolicy[] policies = dfs.getStoragePolicies();
            for (BlockStoragePolicy policy : policies) {
              if (policy.getId() == storagePolicyId) {
                return policy.getName();
              }
            }
          }
        }
      }
    } catch (Throwable e) {
      LOG.warn("failed to get block storage policy of [" + path + "]", e);
    }

    return null;
  }

  /**
   * Are we verifying checksums in HBase?
   * @return True, if hbase is configured to verify checksums, otherwise false.
   */
  public boolean useHBaseChecksum() {
    return useHBaseChecksum;
  }

  /**
   * Close this filesystem object
   */
  @Override
  public void close() throws IOException {
    super.close();
    if (this.noChecksumFs != fs) {
      this.noChecksumFs.close();
    }
  }

  /**
   * Returns a brand new instance of the FileSystem. It does not use the FileSystem.Cache. In newer
   * versions of HDFS, we can directly invoke FileSystem.newInstance(Configuration).
   * @param conf Configuration
   * @return A new instance of the filesystem
   */
  private static FileSystem newInstanceFileSystem(Configuration conf) throws IOException {
    URI uri = FileSystem.getDefaultUri(conf);
    FileSystem fs = null;
    Class<?> clazz = conf.getClass("fs." + uri.getScheme() + ".impl", null);
    if (clazz != null) {
      // This will be true for Hadoop 1.0, or 0.20.
      fs = (FileSystem) org.apache.hadoop.util.ReflectionUtils.newInstance(clazz, conf);
      fs.initialize(uri, conf);
    } else {
      // For Hadoop 2.0, we have to go through FileSystem for the filesystem
      // implementation to be loaded by the service loader in case it has not
      // been loaded yet.
      Configuration clone = new Configuration(conf);
      clone.setBoolean("fs." + uri.getScheme() + ".impl.disable.cache", true);
      fs = FileSystem.get(uri, clone);
    }
    if (fs == null) {
      throw new IOException("No FileSystem for scheme: " + uri.getScheme());
    }

    return fs;
  }

  /**
   * Returns an instance of Filesystem wrapped into the class specified in hbase.fs.wrapper
   * property, if one is set in the configuration, returns unmodified FS instance passed in as an
   * argument otherwise.
   * @param base Filesystem instance to wrap
   * @param conf Configuration
   * @return wrapped instance of FS, or the same instance if no wrapping configured.
   */
  private FileSystem maybeWrapFileSystem(FileSystem base, Configuration conf) {
    try {
      Class<?> clazz = conf.getClass("hbase.fs.wrapper", null);
      if (clazz != null) {
        return (FileSystem) clazz.getConstructor(FileSystem.class, Configuration.class)
          .newInstance(base, conf);
      }
    } catch (Exception e) {
      LOG.error("Failed to wrap filesystem: " + e);
    }
    return base;
  }

  public static boolean addLocationsOrderInterceptor(Configuration conf) throws IOException {
    return addLocationsOrderInterceptor(conf, new ReorderWALBlocks());
  }

  /**
   * Add an interceptor on the calls to the namenode#getBlockLocations from the DFSClient linked to
   * this FileSystem. See HBASE-6435 for the background.
   * <p/>
   * There should be no reason, except testing, to create a specific ReorderBlocks.
   * @return true if the interceptor was added, false otherwise.
   */
  static boolean addLocationsOrderInterceptor(Configuration conf, final ReorderBlocks lrb) {
    if (!conf.getBoolean("hbase.filesystem.reorder.blocks", true)) { // activated by default
      LOG.debug("addLocationsOrderInterceptor configured to false");
      return false;
    }

    FileSystem fs;
    try {
      fs = FileSystem.get(conf);
    } catch (IOException e) {
      LOG.warn("Can't get the file system from the conf.", e);
      return false;
    }

    if (!(fs instanceof DistributedFileSystem)) {
      LOG.debug("The file system is not a DistributedFileSystem. "
        + "Skipping on block location reordering");
      return false;
    }

    DistributedFileSystem dfs = (DistributedFileSystem) fs;
    DFSClient dfsc = dfs.getClient();
    if (dfsc == null) {
      LOG.warn("The DistributedFileSystem does not contain a DFSClient. Can't add the location "
        + "block reordering interceptor. Continuing, but this is unexpected.");
      return false;
    }

    try {
      Field nf = DFSClient.class.getDeclaredField("namenode");
      nf.setAccessible(true);
      Field modifiersField = ReflectionUtils.getModifiersField();
      modifiersField.setAccessible(true);
      modifiersField.setInt(nf, nf.getModifiers() & ~Modifier.FINAL);

      ClientProtocol namenode = (ClientProtocol) nf.get(dfsc);
      if (namenode == null) {
        LOG.warn("The DFSClient is not linked to a namenode. Can't add the location block"
          + " reordering interceptor. Continuing, but this is unexpected.");
        return false;
      }

      ClientProtocol cp1 = createReorderingProxy(namenode, lrb, conf);
      nf.set(dfsc, cp1);
      LOG.info("Added intercepting call to namenode#getBlockLocations so can do block reordering"
        + " using class " + lrb.getClass().getName());
    } catch (NoSuchFieldException | IllegalAccessException | InaccessibleObjectException e) {
      LOG.warn("Can't modify the DFSClient#namenode field to add the location reorder.", e);
      return false;
    }

    return true;
  }

  private static ClientProtocol createReorderingProxy(final ClientProtocol cp,
    final ReorderBlocks lrb, final Configuration conf) {
    return (ClientProtocol) Proxy.newProxyInstance(cp.getClass().getClassLoader(),
      new Class[] { ClientProtocol.class, Closeable.class }, new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
          try {
            if ((args == null || args.length == 0) && "close".equals(method.getName())) {
              RPC.stopProxy(cp);
              return null;
            } else {
              Object res = method.invoke(cp, args);
              if (
                res != null && args != null && args.length == 3
                  && "getBlockLocations".equals(method.getName()) && res instanceof LocatedBlocks
                  && args[0] instanceof String && args[0] != null
              ) {
                lrb.reorderBlocks(conf, (LocatedBlocks) res, (String) args[0]);
              }
              return res;
            }
          } catch (InvocationTargetException ite) {
            // We will have this for all the exception, checked on not, sent
            // by any layer, including the functional exception
            Throwable cause = ite.getCause();
            if (cause == null) {
              throw new RuntimeException("Proxy invocation failed and getCause is null", ite);
            }
            if (cause instanceof UndeclaredThrowableException) {
              Throwable causeCause = cause.getCause();
              if (causeCause == null) {
                throw new RuntimeException("UndeclaredThrowableException had null cause!");
              }
              cause = cause.getCause();
            }
            throw cause;
          }
        }
      });
  }

  /**
   * Interface to implement to add a specific reordering logic in hdfs.
   */
  interface ReorderBlocks {
    /**
     * @param conf - the conf to use
     * @param lbs  - the LocatedBlocks to reorder
     * @param src  - the file name currently read
     * @throws IOException - if something went wrong
     */
    void reorderBlocks(Configuration conf, LocatedBlocks lbs, String src) throws IOException;
  }

  /**
   * We're putting at lowest priority the wal files blocks that are on the same datanode as the
   * original regionserver which created these files. This because we fear that the datanode is
   * actually dead, so if we use it it will timeout.
   */
  static class ReorderWALBlocks implements ReorderBlocks {
    @Override
    public void reorderBlocks(Configuration conf, LocatedBlocks lbs, String src)
      throws IOException {

      ServerName sn = AbstractFSWALProvider.getServerNameFromWALDirectoryName(conf, src);
      if (sn == null) {
        // It's not an WAL
        return;
      }

      // Ok, so it's an WAL
      String hostName = sn.getHostname();
      if (LOG.isTraceEnabled()) {
        LOG.trace(src + " is an WAL file, so reordering blocks, last hostname will be:" + hostName);
      }

      // Just check for all blocks
      for (LocatedBlock lb : lbs.getLocatedBlocks()) {
        DatanodeInfo[] dnis = getLocatedBlockLocations(lb);
        if (dnis != null && dnis.length > 1) {
          boolean found = false;
          for (int i = 0; i < dnis.length - 1 && !found; i++) {
            if (hostName.equals(dnis[i].getHostName())) {
              // advance the other locations by one and put this one at the last place.
              DatanodeInfo toLast = dnis[i];
              System.arraycopy(dnis, i + 1, dnis, i, dnis.length - i - 1);
              dnis[dnis.length - 1] = toLast;
              found = true;
            }
          }
        }
      }
    }
  }

  /**
   * Create a new HFileSystem object, similar to FileSystem.get(). This returns a filesystem object
   * that avoids checksum verification in the filesystem for hfileblock-reads. For these blocks,
   * checksum verification is done by HBase.
   */
  static public FileSystem get(Configuration conf) throws IOException {
    return new HFileSystem(conf, true);
  }

  /**
   * The org.apache.hadoop.fs.FilterFileSystem does not yet support createNonRecursive. This is a
   * hadoop bug and when it is fixed in Hadoop, this definition will go away.
   */
  @Override
  @SuppressWarnings("deprecation")
  public FSDataOutputStream createNonRecursive(Path f, boolean overwrite, int bufferSize,
    short replication, long blockSize, Progressable progress) throws IOException {
    return fs.createNonRecursive(f, overwrite, bufferSize, replication, blockSize, progress);
  }
}
