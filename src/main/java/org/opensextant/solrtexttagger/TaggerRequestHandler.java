/*
 This software was produced for the U. S. Government
 under Contract No. W15P7T-11-C-F600, and is
 subject to the Rights in Noncommercial Computer Software
 and Noncommercial Computer Software Documentation
 Clause 252.227-7014 (JUN 1995)

 Copyright 2013 The MITRE Corporation. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.opensextant.solrtexttagger;

import com.google.common.io.CharStreams;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Terms;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.OpenBitSet;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.*;

/**
 * @author David Smiley - dsmiley@mitre.org
 */
public class TaggerRequestHandler extends RequestHandlerBase {

  private static final String OVERLAPS = "overlaps";
  private final Logger log = LoggerFactory.getLogger(getClass());

  /** Request parameter. */
  public static final String TAGS_LIMIT = "tagsLimit";
  /** Request parameter. */
  public static final String SUB_TAGS = "subTags";//deprecated
  private static final String MATCH_TEXT = "matchText";

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    SolrParams params = SolrParams.wrapDefaults(req.getParams(), SolrParams.toSolrParams(getInitArgs()));

    //--Read params
    final String indexedField = params.get("field");
    if (indexedField == null)
      throw new RuntimeException("required param 'field'");

    final TagClusterReducer tagClusterReducer;
    String overlaps = req.getParams().get(OVERLAPS);
    if (overlaps == null) {//deprecated; should always be specified
      if (req.getParams().getBool(SUB_TAGS, false))//deprecated
        tagClusterReducer = TagClusterReducer.ALL;
      else
        tagClusterReducer = TagClusterReducer.NO_SUB;
    } else if (overlaps.equals("ALL")) {
      tagClusterReducer = TagClusterReducer.ALL;
    } else if (overlaps.equals("NO_SUB")) {
      tagClusterReducer = TagClusterReducer.NO_SUB;
    } else if (overlaps.equals("LONGEST_DOMINANT_RIGHT")) {
      tagClusterReducer = TagClusterReducer.LONGEST_DOMINANT_RIGHT;
    } else {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "unknown tag overlap mode: "+overlaps);
    }

    final int rows = req.getParams().getInt(CommonParams.ROWS, 10000);
    final int tagsLimit = req.getParams().getInt(TAGS_LIMIT, 1000);
    final boolean addMatchText = req.getParams().getBool(MATCH_TEXT, false);
    final SchemaField idSchemaField = req.getSchema().getUniqueKeyField();

    final SolrIndexSearcher searcher = req.getSearcher();

    //--Find the set of documents matching the provided 'fq' (filter query)
    final String corpusFilterQuery = params.get("fq");
    final Bits docBits; //can be null to be all docs
    if (corpusFilterQuery != null) {
      QParser qParser = QParser.getParser(corpusFilterQuery, null, req);
      Query filterQuery = null;
      try {
        filterQuery = qParser.parse();
      } catch (SyntaxError e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
      }
      docBits = searcher.getDocSet(filterQuery).getBits();
    } else {
      docBits = searcher.getAtomicReader().getLiveDocs();
    }

    //--Get posted data
    Reader reader = null;
    Iterable<ContentStream> streams = req.getContentStreams();
    if (streams != null) {
      Iterator<ContentStream> iter = streams.iterator();
      if (iter.hasNext()) {
        reader = iter.next().getReader();
      }
      if (iter.hasNext()) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            getClass().getSimpleName()+" does not support multiple ContentStreams");
      }
    }
    if (reader == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          getClass().getSimpleName()+" requires text to be POSTed to it");
    }
    final String bufferedInput;
    if (addMatchText) {
      //read the input fully into a String buffer we'll use later to get
      // the match text, then replace the input with a reader wrapping the buffer.
      bufferedInput = CharStreams.toString(reader);//(closes reader)
      reader = new StringReader(bufferedInput);
    } else {
      bufferedInput = null;//not used
    }

    final OpenBitSet matchDocIdsBS = new OpenBitSet(searcher.maxDoc());
    final List tags = new ArrayList(2000);

    try {
      Analyzer analyzer = req.getSchema().getField(indexedField).getType().getQueryAnalyzer();
      TokenStream tokenStream = analyzer.tokenStream("", reader);

      Terms terms = searcher.getAtomicReader().terms(indexedField);
      if (terms == null)
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            "field "+indexedField+" has no indexed data");
      new Tagger(terms, docBits, tokenStream, tagClusterReducer) {
        @SuppressWarnings("unchecked")
        @Override
        protected void tagCallback(int startOffset, int endOffset, Object docIdsKey) {
          if (tags.size() >= tagsLimit)
            return;
          NamedList tag = new NamedList();
          tag.add("startOffset", startOffset);
          tag.add("endOffset", endOffset);
          if (addMatchText)
            tag.add("matchText", bufferedInput.substring(startOffset,
                endOffset));
          //below caches, and also flags matchDocIdsBS
          tag.add("ids", lookupSchemaDocIds(docIdsKey));
          tags.add(tag);
        }

        Map<Object,List> docIdsListCache = new HashMap<Object, List>(2000);

        ValueSourceAccessor uniqueKeyCache = new ValueSourceAccessor(searcher,
            idSchemaField.getType().getValueSource(idSchemaField, null));

        @SuppressWarnings("unchecked")
        private List lookupSchemaDocIds(Object docIdsKey) {
          List schemaDocIds = docIdsListCache.get(docIdsKey);
          if (schemaDocIds != null)
            return schemaDocIds;
          DocsEnum docIds = lookupDocIds(docIdsKey);
          //translate lucene docIds to schema ids
          schemaDocIds = new ArrayList();
          try {
            int docId;
            while ((docId = docIds.nextDoc()) != DocsEnum.NO_MORE_DOCS) {
              matchDocIdsBS.set(docId);//also, flip docid in bitset
              schemaDocIds.add(uniqueKeyCache.objectVal(docId));//translates here
            }
            assert !schemaDocIds.isEmpty();
          } catch (IOException e) {
            throw new RuntimeException(e);
          }

          docIdsListCache.put(docIdsKey, schemaDocIds);
          return schemaDocIds;
        }

      }.process();
    } finally {
      reader.close();
    }
    rsp.add("tagsCount",tags.size());
    rsp.add("tags", tags);

    ReturnFields returnFields = new SolrReturnFields( req );
    rsp.setReturnFields( returnFields );

    //Now we must supply a Solr DocList and add it to the response.
    //  Typically this is gotten via a SolrIndexSearcher.search(), but in this case we
    //  know exactly what documents to return, the order doesn't matter nor does
    //  scoring.
    //  Ideally an implementation of DocList could be directly implemented off
    //  of a BitSet, but there are way too many methods to implement for a minor
    //  payoff.
    int matchDocs = (int) matchDocIdsBS.cardinality();
    int[] docIds = new int[ Math.min(rows, matchDocs) ];
    DocIdSetIterator docIdIter = matchDocIdsBS.iterator();
    for (int i = 0; i < docIds.length; i++) {
      docIds[i] = docIdIter.nextDoc();
    }
    DocList docs = new DocSlice(0, docIds.length, docIds, null, matchDocs, 1f);
    rsp.add("matchingDocs", docs);//TODO use normal location for docs, not this
  }

  @Override
  public String getDescription() {
    return "Processes input text to find stand-off named tags against a large corpus.";
  }

  @Override
  public String getSource() {
    return "$HeadURL$";
  }

}

/** See LUCENE-4541 or {@link org.apache.solr.response.transform.ValueSourceAugmenter}. */
class ValueSourceAccessor {
  // implement FunctionValues ?
  private final List<AtomicReaderContext> readerContexts;
  private final FunctionValues[] docValuesArr;
  private final ValueSource valueSource;
  private final Map fContext;

  private int localId;
  private FunctionValues values;

  public ValueSourceAccessor(IndexSearcher searcher, ValueSource valueSource) {
    readerContexts = searcher.getIndexReader().leaves();
    this.valueSource = valueSource;
    docValuesArr = new FunctionValues[readerContexts.size()];
    fContext = ValueSource.newContext(searcher);
  }

  private void setState(int docid) {
    int idx = ReaderUtil.subIndex(docid, readerContexts);
    AtomicReaderContext rcontext = readerContexts.get(idx);
    values = docValuesArr[idx];
    if (values == null) {
      try {
        docValuesArr[idx] = values = valueSource.getValues(fContext, rcontext);
      } catch (IOException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      }
    }
    localId = docid - rcontext.docBase;
  }

  public Object objectVal(int docid) {
    setState(docid);
    return values.objectVal(localId);
  }

  //...
}
