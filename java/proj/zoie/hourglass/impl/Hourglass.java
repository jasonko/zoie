package proj.zoie.hourglass.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;

import proj.zoie.api.DataConsumer;
import proj.zoie.api.DirectoryManager;
import proj.zoie.api.DocIDMapper;
import proj.zoie.api.DocIDMapperFactory;
import proj.zoie.api.IndexReaderFactory;
import proj.zoie.api.ZoieException;
import proj.zoie.api.ZoieIndexReader;
import proj.zoie.api.ZoieMultiReader;
import proj.zoie.api.indexing.IndexReaderDecorator;
import proj.zoie.api.indexing.ZoieIndexableInterpreter;
import proj.zoie.impl.indexing.ZoieConfig;
import proj.zoie.impl.indexing.ZoieSystem;

public class Hourglass<R extends IndexReader, V> implements IndexReaderFactory<ZoieIndexReader<R>>, DataConsumer<V>
{
  public static final Logger log = Logger.getLogger(Hourglass.class);
  private final HourglassDirectoryManagerFactory _dirMgrFactory;
  private DirectoryManager _dirMgr;
  private final ZoieIndexableInterpreter<V> _interpreter;
  private final IndexReaderDecorator<R> _decorator;
  private final ZoieConfig _zConfig;
  private volatile ZoieSystem<R, V> _currentZoie;
  private volatile ZoieSystem<R, V> _oldZoie = null;
  private final List<ZoieIndexReader<R>> archiveList = new ArrayList<ZoieIndexReader<R>>();
  public Hourglass(HourglassDirectoryManagerFactory dirMgrFactory, ZoieIndexableInterpreter<V> interpreter, IndexReaderDecorator<R> readerDecorator,ZoieConfig zoieConfig)
  {
    _zConfig = zoieConfig;
    _zConfig.setDocidMapperFactory(new DocIDMapperFactory(){

      public DocIDMapper getDocIDMapper(ZoieMultiReader<?> reader)
      {
        return new NullDocIDMapper();
      }});
    _dirMgrFactory = dirMgrFactory;
    _dirMgr = _dirMgrFactory.getDirectoryManager();
    _dirMgrFactory.clearRecentlyChanged();
    _interpreter = interpreter;
    _decorator = readerDecorator;
    loadArchives();
    _currentZoie = createZoie(_dirMgr);
    _currentZoie.start();
  }
  private void loadArchives()
  {
    long t0 = System.currentTimeMillis();
    List<Directory> dirs = _dirMgrFactory.getAllArchivedDirectories();
    for(Directory dir : dirs)
    {
      IndexReader reader;
      try
      {
        reader = IndexReader.open(dir,true);
        ZoieMultiReader<R> zoiereader = new ZoieMultiReader<R>(reader, _decorator);
        archiveList.add(zoiereader);
      } catch (CorruptIndexException e)
      {
        log.error("corruptedIndex", e);
      } catch (IOException e)
      {
        log.error("IOException", e);
      }
    }
    log.info("load "+dirs.size()+" archive Indices in " + (System.currentTimeMillis() - t0) + "ms");
  }
  private ZoieSystem<R, V> createZoie(DirectoryManager dirmgr)
  {
    return new ZoieSystem<R, V>(dirmgr, _interpreter, _decorator, _zConfig);
  }

  /* (non-Javadoc)
   * @see proj.zoie.api.IndexReaderFactory#getAnalyzer()
   */
  public Analyzer getAnalyzer()
  {
    return _zConfig.getAnalyzer();
  }

  /**
   * return a list of ZoieIndexReaders. These readers are reference counted and this method
   * should be used in pair with returnIndexReaders(List<ZoieIndexReader<R>> readers) {@link #returnIndexReaders(List)}.
   * It is typical that we create a MultiReader from these readers. When creating MultiReader, it should be created with
   * the closeSubReaders parameter set to false in order to do reference counting correctly.
   * @see proj.zoie.hourglass.impl.Hourglass#returnIndexReaders(List)
   * @see proj.zoie.api.IndexReaderFactory#getIndexReaders()
   */
  public List<ZoieIndexReader<R>> getIndexReaders() throws IOException
  {
    List<ZoieIndexReader<R>> list = new ArrayList<ZoieIndexReader<R>>();
    // add the archived index readers
    for(ZoieIndexReader<R> r : archiveList)
    {
      r.incRef();
      list.add(r);
    }
    if (_oldZoie!=null)
    {
      if(_oldZoie.getCurrentBatchSize()+_oldZoie.getCurrentDiskBatchSize()+_oldZoie.getCurrentMemBatchSize()==0)
      {
        // all events on disk.
        log.info("shutting down ... " + _oldZoie.getAdminMBean().getIndexDir());
        _oldZoie.shutdown();
        String dirName = _oldZoie.getAdminMBean().getIndexDir();
        IndexReader reader = IndexReader.open(new SimpleFSDirectory(new File(dirName)),true);
        _oldZoie = null;
        ZoieMultiReader<R> zoiereader = new ZoieMultiReader<R>(reader, _decorator);
        archiveList.add(zoiereader);
        zoiereader.incRef();
        list.add(zoiereader);
      } else
      {
        List<ZoieIndexReader<R>> oldlist = _oldZoie.getIndexReaders();// already incRef.
        list.addAll(oldlist);
      }
    }
    // add the index readers for the current realtime index
    List<ZoieIndexReader<R>> readers = _currentZoie.getIndexReaders(); // already incRef
    list.addAll(readers);
    return list;
  }

  /* (non-Javadoc)
   * @see proj.zoie.api.IndexReaderFactory#returnIndexReaders(java.util.List)
   */
  public void returnIndexReaders(List<ZoieIndexReader<R>> readers)
  {
    _currentZoie.returnIndexReaders(readers);
  }

  /* (non-Javadoc)
   * @see proj.zoie.api.DataConsumer#consume(java.util.Collection)
   */
  public void consume(Collection<DataEvent<V>> data)
      throws ZoieException
  {
    // TODO  need to check time boundary. When we hit boundary, we need to trigger DM to 
    // use new dir for zoie and the old one will be archive.
    if (!_dirMgrFactory.updateDirectoryManager())
    {
      _currentZoie.consume(data);
      return;
    }
    // new time period
    _oldZoie = _currentZoie;
    _dirMgr = _dirMgrFactory.getDirectoryManager();
    _dirMgrFactory.clearRecentlyChanged();
    _currentZoie = createZoie(_dirMgr);
    _currentZoie.start();
    _currentZoie.consume(data);
  }
  
  public void shutdown()
  {
    _currentZoie.shutdown();
    for(ZoieIndexReader<R> r : archiveList)
    {
      try
      {
        r.decRef();
      } catch (IOException e)
      {
        log.error("error decRef during shutdown", e);
      }
      log.info("refCount at shutdown: " + r.getRefCount());
    }
    log.info("shut down");
  }

  /* (non-Javadoc)
   * @see proj.zoie.api.DataConsumer#getVersion()
   */
  public long getVersion()
  {
    return _currentZoie.getVersion();
  }
  public final class NullDocIDMapper implements DocIDMapper
  {
    public int getDocID(long uid)
    {
      throw new UnsupportedOperationException();
    }

    public Object getDocIDArray(long[] uids)
    {
      throw new UnsupportedOperationException();
    }

    public Object getDocIDArray(int[] uids)
    {
      throw new UnsupportedOperationException();
    }

    public int getReaderIndex(long uid)
    {
      throw new UnsupportedOperationException();
    }

    public int[] getStarts()
    {
      throw new UnsupportedOperationException();
    }

    public ZoieIndexReader[] getSubReaders()
    {
      throw new UnsupportedOperationException();
    }

    public int quickGetDocID(long uid)
    {
      throw new UnsupportedOperationException();
    }
    
  }
}
