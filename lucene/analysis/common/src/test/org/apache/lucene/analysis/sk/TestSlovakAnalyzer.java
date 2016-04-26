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
package org.apache.lucene.analysis.sk;


import java.io.IOException;

import org.apache.lucene.analysis.BaseTokenStreamTestCase;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.util.CharArraySet;

/**
 * Test the SlovakAnalyzer
 */
public class TestSlovakAnalyzer extends BaseTokenStreamTestCase {
  
  /** This test fails with NPE when the 
   * stopwords file is missing in classpath */
  public void testResourcesAvailable() {
    new SlovakAnalyzer().close();
  }
  
  public void testBasics() throws Exception {
    Analyzer analyzer = new SlovakAnalyzer();
    assertAnalyzesTo(analyzer, "listina základních práv evropské unie", 
                new String[] { "list", "základn", "práv", "evropsk", "uni" });
    analyzer.close();
  }
  
  /** Test stopword removal */
  public void testStopWord() throws Exception {
    Analyzer analyzer = new SlovakAnalyzer();
    assertAnalyzesTo(analyzer, "aby", new String[] { });
    analyzer.close();
  }
  
  /** Test stemmer exceptions */
  public void testStemExclusion() throws IOException{
    CharArraySet set = new CharArraySet(1, true);
    set.add("chlapom");
    Analyzer analyzer = new SlovakAnalyzer(CharArraySet.EMPTY_SET, set);
    assertAnalyzesTo(analyzer, "chlapom", new String[] {"chlapom"});
    analyzer.close();
  }
  
  /** blast some random strings through the analyzer */
  public void testRandomStrings() throws Exception {
    Analyzer analyzer = new SlovakAnalyzer();
    checkRandomData(random(), analyzer, 1000*RANDOM_MULTIPLIER);
    analyzer.close();
  }
}
