package proj.zoie.solr;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.FilterIndexReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.IndexReaderFactory;

import proj.zoie.api.ZoieIndexReader;
import proj.zoie.impl.indexing.ZoieSystem;

public class ZoieSolrIndexReaderFactory extends IndexReaderFactory {
	private ZoieSystem<IndexReader,DocumentWithID> _zoieSystem = null;
	private List<ZoieIndexReader<IndexReader>> _readerList = null; 
	@Override
	public void init(NamedList args) {
		super.init(args);
	}
	
	public void setZoieSystem(ZoieSystem<IndexReader,DocumentWithID> zoieSystem){
		_zoieSystem = zoieSystem;
	}

	@Override
	public IndexReader newReader(Directory dir, boolean readOnly)
			throws IOException {
		if (_zoieSystem!=null){
			
			List<ZoieIndexReader<IndexReader>> readerList = _readerList;
			_readerList	= _zoieSystem.getIndexReaders();
			if (readerList!=null){
				_zoieSystem.returnIndexReaders(_readerList);
			}
			return new ZoieSolrMultiReader<IndexReader>(_readerList, _zoieSystem);
		}
		else{
			return new InitialIndexReader(IndexReader.open(dir, null, readOnly, termInfosIndexDivisor));
		}
		
	}

	@Override
	protected void finalize() throws Throwable {
		try{
			if (_zoieSystem!=null){
				_zoieSystem.shutdown();
			}
		}
		finally{
			super.finalize();
		}
	}
	
	private class InitialIndexReader extends FilterIndexReader{
		public InitialIndexReader(IndexReader in) {
			super(in);
		}

		@Override
		public synchronized IndexReader reopen() throws CorruptIndexException,
				IOException {
			return reopen(true);
		}

		@Override
		public synchronized IndexReader reopen(boolean openReadOnly)
				throws CorruptIndexException, IOException {
			if (ZoieSolrIndexReaderFactory.this._zoieSystem==null){
				return this;
			}
			else{
				_readerList	= _zoieSystem.getIndexReaders();
				return new ZoieSolrMultiReader<IndexReader>(_readerList, _zoieSystem);
			}
		}

		@Override
		public synchronized IndexReader reopen(IndexCommit commit)
				throws CorruptIndexException, IOException {
			return reopen(true);
		}
	}

}
