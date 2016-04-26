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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.BaseTokenStreamTestCase;
import org.apache.lucene.analysis.MockTokenizer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.KeywordTokenizer;

/**
 * Basic tests for Slovak Light Stemmer.
 * <p>
 * We test some n/adj templates and some high frequency
 * terms from mixed corpora.
 * <p>
 * Note: it's algorithmic, so some stems don't conflate
 */
public class TestSlovakLightStemmer extends BaseTokenStreamTestCase {
  private Analyzer a;
  
  @Override
  public void setUp() throws Exception {
    super.setUp();
    a = new Analyzer() {
      @Override
      protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new MockTokenizer(MockTokenizer.WHITESPACE, false);
        return new TokenStreamComponents(tokenizer, new SlovakLightStemFilter(tokenizer));
      }
    };
  }
  
  @Override
  public void tearDown() throws Exception {
    a.close();
    super.tearDown();
  }

  public void testMasculineNounsI() throws IOException {
    checkOneTerm(a, "chlap",    "chlap");  // nom. sg.
    checkOneTerm(a, "chlapi",   "chlap");  // nom. pl.
    checkOneTerm(a, "chlapa",   "chlap");  // gen. sg.
    checkOneTerm(a, "chlapov",  "chlap");  // gen. pl.
    checkOneTerm(a, "chlapovi", "chlap");  // dat. sg.
    checkOneTerm(a, "chlapom",  "chlap");  // dat. pl.
    checkOneTerm(a, "chlapa",   "chlap");  // acc. sg.
    checkOneTerm(a, "chlapov",  "chlap");  // acc. pl.
    checkOneTerm(a, "chlapovi", "chlap");  // loc. sg.
    checkOneTerm(a, "chlapoch", "chlap");  // loc. pl.
    checkOneTerm(a, "chlapom",  "chlap");  // ins. sg.
    checkOneTerm(a, "chlapmi",  "chlap");  // ins. pl.
    
    checkOneTerm(a, "chlapec",   "chlapec");  // nom. sg.
    checkOneTerm(a, "chlapci",   "chlapk");   // nom. pl.
    checkOneTerm(a, "chlapca",   "chlapc");   // gen. sg.
    checkOneTerm(a, "chlapcov",  "chlapc");   // gen. pl.
    checkOneTerm(a, "chlapcovi", "chlapc");   // dat. sg.
    checkOneTerm(a, "chlapcom",  "chlapc");   // dat. pl.
    checkOneTerm(a, "chlapca",   "chlapc");   // acc. sg.
    checkOneTerm(a, "chlapcov",  "chlapc");   // acc. pl.
    checkOneTerm(a, "chlapcovi", "chlapc");   // loc. sg.
    checkOneTerm(a, "chlapcoch", "chlapc");   // loc. pl.
    checkOneTerm(a, "chlapcom",  "chlapc");   // ins. sg.
    checkOneTerm(a, "chlapcami", "chlapc");   // ins. pl.
  }

  public void testMasculineNounsII() throws IOException {
    checkOneTerm(a, "hrdina",    "hrdin");    // nom. sg.
    checkOneTerm(a, "hrdinovia", "hrdinovi"); // nom. pl.
    checkOneTerm(a, "hrdinu",    "hrdinu");   // gen. sg.
    checkOneTerm(a, "hrdinov",   "hrdin");    // gen. pl.
    checkOneTerm(a, "hrdinovi",  "hrdin");    // dat. sg.
    checkOneTerm(a, "hrdinom",   "hrdin");    // dat. pl.
    checkOneTerm(a, "hrdinu",    "hrdinu");   // acc. sg.
    checkOneTerm(a, "hrdinov",   "hrdin");    // acc. pl.
    checkOneTerm(a, "hrdinovi",  "hrdin");    // loc. sg.
    checkOneTerm(a, "hrdinoch",  "hrdin");    // loc. pl.
    checkOneTerm(a, "hrdinom",   "hrdin");    // ins. sg.
    checkOneTerm(a, "hrdinami",  "hrdin");    // ins. pl.
  }

  public void testMasculineNounsIII() throws IOException {
    checkOneTerm(a, "dub",    "dub");    // nom. sg.
    checkOneTerm(a, "duby",   "dub");    // nom. pl.
    checkOneTerm(a, "duba",   "dub");    // gen. sg.
    checkOneTerm(a, "dubov",  "dubov");  // gen. pl.
    checkOneTerm(a, "dubu",   "dubu");   // dat. sg.
    checkOneTerm(a, "dubom",  "dub");    // dat. pl.
    checkOneTerm(a, "dub",    "dub");    // acc. sg.
    checkOneTerm(a, "duby",   "dub");    // acc. pl.
    checkOneTerm(a, "dube",   "dub");    // loc. sg.
    checkOneTerm(a, "duboch", "dub");    // loc. pl.
    checkOneTerm(a, "dubom",  "dub");    // ins. sg.
    checkOneTerm(a, "dubmi",  "dub");    // ins. pl.
  }

  public void testMasculineNounsIV() throws IOException {
    checkOneTerm(a, "stroj",    "stroj");    // nom. sg.
    checkOneTerm(a, "stroje",   "stroj");    // nom. pl.
    checkOneTerm(a, "stroja",   "stroj");    // gen. sg.
    checkOneTerm(a, "strojov",  "stroj");    // gen. pl.
    checkOneTerm(a, "stroju",   "stroju");   // dat. sg.
    checkOneTerm(a, "strojom",  "stroj");    // dat. pl.
    checkOneTerm(a, "stroj",    "stroj");    // acc. sg.
    checkOneTerm(a, "stroje",   "stroj");    // acc. pl.
    checkOneTerm(a, "stroji",   "stroj");    // loc. sg.
    checkOneTerm(a, "strojoch", "stroj");    // loc. pl.
    checkOneTerm(a, "strojom",  "stroj");    // ins. sg.
    checkOneTerm(a, "strojmi",  "stroj");    // ins. pl.
  }
  
  public void testMasculineNounsLoan() throws IOException {
    checkOneTerm(a, "kuli",     "kul");      // nom. sg.
    checkOneTerm(a, "kuliovia", "kuliovi");  // nom. pl.
    checkOneTerm(a, "kuliho",   "kul");      // gen. sg.
    checkOneTerm(a, "kuliov",   "kuli");     // gen. pl.
    checkOneTerm(a, "kulimu",   "kul");      // dat. sg.
    checkOneTerm(a, "kuliom",   "kuli");     // dat. pl.
    checkOneTerm(a, "kuliho",   "kul");      // acc. sg.
    checkOneTerm(a, "kuliov",   "kuli");     // acc. pl.
    checkOneTerm(a, "kulim",    "kulim");    // loc. sg.
    checkOneTerm(a, "kulioch",  "kuli");     // loc. pl.
    checkOneTerm(a, "kulim",    "kulim");    // ins. sg.
    checkOneTerm(a, "kuliami",  "kuli");     // ins. pl.
  }

  public void testFeminineNounsI() throws IOException {
    checkOneTerm(a, "žena",   "žen");   // nom. sg.
    checkOneTerm(a, "ženy",   "žen");   // nom. pl.
    checkOneTerm(a, "ženy",   "žen");   // gen. sg.
    checkOneTerm(a, "žien",   "žien");  // gen. pl.
    checkOneTerm(a, "žene",   "žen");   // dat. sg.
    checkOneTerm(a, "ženám",  "žen");   // dat. pl.
    checkOneTerm(a, "ženu",   "ženu");  // acc. sg.
    checkOneTerm(a, "ženy",   "žen");   // acc. pl.
    checkOneTerm(a, "žene",   "žen");   // loc. sg.
    checkOneTerm(a, "ženách", "žen");   // loc. pl.
    checkOneTerm(a, "ženou",  "žen");   // ins. sg.
    checkOneTerm(a, "ženami", "žen");   // ins. pl.
    
    checkOneTerm(a, "mama",   "mam");   // nom. sg.
    checkOneTerm(a, "mamy",   "mam");   // nom. pl.
    checkOneTerm(a, "mamy",   "mam");   // gen. sg.
    checkOneTerm(a, "mám",    "mám");   // gen. pl.
    checkOneTerm(a, "mame",   "mam");   // dat. sg.
    checkOneTerm(a, "mamám",  "mam");   // dat. pl.
    checkOneTerm(a, "mamu",   "mamu");  // acc. sg.
    checkOneTerm(a, "mamy",   "mam");   // acc. pl.
    checkOneTerm(a, "mame",   "mam");   // loc. sg.
    checkOneTerm(a, "mamách", "mam");   // loc. pl.
    checkOneTerm(a, "mamou",  "mam");   // ins. sg.
    checkOneTerm(a, "mamami", "mam");   // ins. pl.
  }
  
  public void testFeminineNounsII() throws IOException {
    checkOneTerm(a, "ulica",    "ulic");      // nom. sg.
    checkOneTerm(a, "ulice",    "ulik");      // nom. pl.
    checkOneTerm(a, "ulice",    "ulik");      // gen. sg.
    checkOneTerm(a, "ulíc",     "ulíc");      // gen. pl.
    checkOneTerm(a, "ulici",    "ulik");      // dat. sg.
    checkOneTerm(a, "uliciam",  "uliciam");   // dat. pl.
    checkOneTerm(a, "ulicu",    "ulicu");     // acc. sg.
    checkOneTerm(a, "ulice",    "ulik");      // acc. pl.
    checkOneTerm(a, "ulici",    "ulik");      // loc. sg.
    checkOneTerm(a, "uliciach", "uliciach");  // loc. pl.
    checkOneTerm(a, "ulicou",   "ulic");      // ins. sg.
    checkOneTerm(a, "ulicami",  "ulic");      // ins. pl.
  }

  public void testFeminineNounsIII() throws IOException {
    checkOneTerm(a, "dlaň",     "dlaň");      // nom. sg.
    checkOneTerm(a, "dlane",    "dlan");      // nom. pl.
    checkOneTerm(a, "dlane",    "dlan");      // gen. sg.
    checkOneTerm(a, "dlaní",    "dlan");      // gen. pl.
    checkOneTerm(a, "dlani",    "dlan");      // dat. sg.
    checkOneTerm(a, "dlaniam",  "dlaniam");   // dat. pl.
    checkOneTerm(a, "dlaň",     "dlaň");      // acc. sg.
    checkOneTerm(a, "dlane",    "dlan");      // acc. pl.
    checkOneTerm(a, "dlani",    "dlan");      // loc. sg.
    checkOneTerm(a, "dlaniach", "dlaniach");  // loc. pl.
    checkOneTerm(a, "dlaňou",   "dlaň");      // ins. sg.
    checkOneTerm(a, "dlaňami",  "dlaň");      // ins. pl.
  }

  public void testFeminineNounsIV() throws IOException {
    checkOneTerm(a, "kosť",     "kosť");      // nom. sg.
    checkOneTerm(a, "kosti",    "kost");      // nom. pl.
    checkOneTerm(a, "kosti",    "kost");      // gen. sg.
    checkOneTerm(a, "kostí",    "kost");      // gen. pl.
    checkOneTerm(a, "kosti",    "kost");      // dat. sg.
    checkOneTerm(a, "kostiam",  "kostiam");   // dat. pl.
    checkOneTerm(a, "kosť",     "kosť");      // acc. sg.
    checkOneTerm(a, "kosti",    "kost");      // acc. pl.
    checkOneTerm(a, "kosti",    "kost");      // loc. sg.
    checkOneTerm(a, "kostiach", "kostiach");  // loc. pl.
    checkOneTerm(a, "kosťou",   "kosť");      // ins. sg.
    checkOneTerm(a, "kosťami",  "kosť");      // ins. pl.
  }

  public void testNeuterNounsI() throws IOException {
    checkOneTerm(a, "mesto",    "mest");     // nom. sg.
    checkOneTerm(a, "mestá",    "mest");     // nom. pl.
    checkOneTerm(a, "mesta",    "mest");     // gen. sg.
    checkOneTerm(a, "miest",    "miest");    // gen. pl.
    checkOneTerm(a, "mestu",    "mestu");    // dat. sg.
    checkOneTerm(a, "mestám",   "mest");     // dat. pl.
    checkOneTerm(a, "mesto",    "mest");     // acc. sg.
    checkOneTerm(a, "mestá",    "mest");     // acc. pl.
    checkOneTerm(a, "meste",    "mest");     // loc. sg.
    checkOneTerm(a, "mestách",  "mest");     // loc. pl.
    checkOneTerm(a, "mestom",   "mest");     // ins. sg.
    checkOneTerm(a, "mestami",  "mest");     // ins. pl.
  }

  public void testNeuterNounsII() throws IOException {
    checkOneTerm(a, "srdce",    "srdk");      // nom. sg.
    checkOneTerm(a, "srdcia",   "srdci");     // nom. pl.
    checkOneTerm(a, "srdca",    "srdc");      // gen. sg.
    checkOneTerm(a, "sŕdc",     "sŕdc");      // gen. pl.
    checkOneTerm(a, "srdcu",    "srdcu");     // dat. sg.
    checkOneTerm(a, "srdciam",  "srdciam");   // dat. pl.
    checkOneTerm(a, "srdce",    "srdk");      // acc. sg.
    checkOneTerm(a, "srdcia",   "srdci");     // acc. pl.
    checkOneTerm(a, "srdci",    "srdk");      // loc. sg.
    checkOneTerm(a, "srdciach", "srdciach");  // loc. pl.
    checkOneTerm(a, "srdcom",   "srdc");      // ins. sg.
    checkOneTerm(a, "srdcami",  "srdc");      // ins. pl.;
  }

  public void testNeuterNounsIII() throws IOException {
    checkOneTerm(a, "vysvedčenie",   "vysvedčeni");     // nom. sg.
    checkOneTerm(a, "vysvedčenia",   "vysvedčeni");     // nom. pl.
    checkOneTerm(a, "vysvedčenia",   "vysvedčeni");     // gen. sg.
    checkOneTerm(a, "vysvedčení",    "vysvedčen");      // gen. pl.
    checkOneTerm(a, "vysvedčeniu",   "vysvedčeniu");    // dat. sg.
    checkOneTerm(a, "vysvedčeniam",  "vysvedčeniam");   // dat. pl.
    checkOneTerm(a, "vysvedčenie",   "vysvedčeni");     // acc. sg.
    checkOneTerm(a, "vysvedčenia",   "vysvedčeni");     // acc. pl.
    checkOneTerm(a, "vysvedčení",    "vysvedčen");      // loc. sg.
    checkOneTerm(a, "vysvedčeniach", "vysvedčeniach");  // loc. pl.
    checkOneTerm(a, "vysvedčením",   "vysvedče");       // ins. sg.
    checkOneTerm(a, "vysvedčeniami", "vysvedčeni");     // ins. pl.
  }

  public void testNeuterNounsIV() throws IOException {
    checkOneTerm(a, "dievča",      "dievč");       // nom. sg.
    checkOneTerm(a, "dievčatá",    "dievčat");     // nom. pl.
    checkOneTerm(a, "dievčence",   "dievčenk");    // nom. pl.
    checkOneTerm(a, "dievčaťa",    "dievč");       // gen. sg.
    checkOneTerm(a, "dievčiat",    "dievči");      // gen. pl.
    checkOneTerm(a, "dievčeniec",  "dievčeniec");  // gen. pl.
    checkOneTerm(a, "dievčaťu",    "dievčaťu");    // dat. sg.
    checkOneTerm(a, "dievčatám",   "dievčat");     // dat. pl.
    checkOneTerm(a, "dievčencom",  "dievčenc");    // dat. pl.
    checkOneTerm(a, "dievča",      "dievč");       // acc. sg.
    checkOneTerm(a, "dievčatá",    "dievčat");     // acc. pl.
    checkOneTerm(a, "dievčence",   "dievčenk");    // acc. pl.
    checkOneTerm(a, "dievčati",    "dievčat");     // loc. sg.
    checkOneTerm(a, "dievčatách",  "dievčat");     // loc. pl.
    checkOneTerm(a, "dievčencoch", "dievčenc");    // loc. pl.
    checkOneTerm(a, "dievčaťom",   "dievč");       // ins. sg.
    checkOneTerm(a, "dievčatami",  "dievčat");     // ins. pl.
    checkOneTerm(a, "dievčencami", "dievčenc");    // ins. pl.
  }

  public void testAdjectivesI() throws IOException {
    checkOneTerm(a, "pekný",     "pekn");   // m. nom. sg.
    checkOneTerm(a, "pekného",   "pekn");   // m. gen. sg.
    checkOneTerm(a, "peknému",   "pekn");   // m. dat. sg.
    checkOneTerm(a, "pekný",     "pekn");   // m. acc. inam. sg.
    checkOneTerm(a, "pekného",   "pekn");   // m. acc. anim. sg.
    checkOneTerm(a, "peknom",    "pekn");   // m. loc. sg.
    checkOneTerm(a, "pekným",    "pekn");   // m. ins. sg.
    checkOneTerm(a, "pekné",     "pekn");   // n. nom. sg.
    checkOneTerm(a, "pekného",   "pekn");   // n. gen. sg.
    checkOneTerm(a, "peknému",   "pekn");   // n. dat. sg.
    checkOneTerm(a, "pekné",     "pekn");   // n. acc. sg.
    checkOneTerm(a, "peknom",    "pekn");   // n. loc. sg.
    checkOneTerm(a, "pekným",    "pekn");   // n. ins. sg.
    checkOneTerm(a, "pekná",     "pekn");   // f. nom. sg.
    checkOneTerm(a, "peknej",    "pekn");   // f. gen. sg.
    checkOneTerm(a, "peknej",    "pekn");   // f. dat. sg.
    checkOneTerm(a, "peknú",     "pekn");   // f. acc. sg.
    checkOneTerm(a, "peknej",    "pekn");   // f. loc. sg.
    checkOneTerm(a, "peknou",    "pekn");   // f. ins. sg.
    checkOneTerm(a, "pekné",     "pekn");   // nom. inam. pl.
    checkOneTerm(a, "pekní",     "pekn");   // nom. anim. pl.
    checkOneTerm(a, "pekných",   "pekn");   // gen. pl.
    checkOneTerm(a, "pekným",    "pekn");   // dat. pl.
    checkOneTerm(a, "pekné",     "pekn");   // acc. inam. pl.
    checkOneTerm(a, "pekných",   "pekn");   // acc. anim. pl.
    checkOneTerm(a, "pekných",   "pekn");   // loc. pl.
    checkOneTerm(a, "peknými",   "pekn");   // ins. pl.
  }

  public void testAdjectivesII() throws IOException {
    checkOneTerm(a, "cudzí",     "cudz");      // m. nom. sg.
    checkOneTerm(a, "cudzieho",  "cudzieh");   // m. gen. sg.
    checkOneTerm(a, "cudziemu",  "cudziemu");  // m. dat. sg.
    checkOneTerm(a, "cudzí",     "cudz");      // m. acc. inam. sg.
    checkOneTerm(a, "cudzieho",  "cudzieh");   // m. acc. anim. sg.
    checkOneTerm(a, "cudzom",    "cudz");      // m. loc. sg.
    checkOneTerm(a, "cudzím",    "cud");       // m. ins. sg.
    checkOneTerm(a, "cudzie",    "cudzi");     // n. nom. sg.
    checkOneTerm(a, "cudzieho",  "cudzieh");   // n. gen. sg.
    checkOneTerm(a, "cudziemu",  "cudziemu");  // n. dat. sg.
    checkOneTerm(a, "cudzie",    "cudzi");     // n. acc. sg.
    checkOneTerm(a, "cudzom",    "cudz");      // n. loc. sg.
    checkOneTerm(a, "cudzím",    "cud");       // n. ins. sg.
    checkOneTerm(a, "cudzia",    "cudzi");     // f. nom. sg.
    checkOneTerm(a, "cudzej",    "cudz");      // f. gen. sg.
    checkOneTerm(a, "cudzej",    "cudz");      // f. dat. sg.
    checkOneTerm(a, "cudziu",    "cudziu");    // f. acc. sg.
    checkOneTerm(a, "cudzej",    "cudz");      // f. loc. sg.
    checkOneTerm(a, "cudzou",    "cudz");      // f. ins. sg.
    checkOneTerm(a, "cudzie",    "cudzi");     // nom. inam. pl.
    checkOneTerm(a, "cudzí",     "cudz");      // nom. anim. pl.
    checkOneTerm(a, "cudzích",   "cudz");      // gen. pl.
    checkOneTerm(a, "cudzím",    "cud");       // dat. pl.
    checkOneTerm(a, "cudzie",    "cudzi");     // acc. inam. pl.
    checkOneTerm(a, "cudzích",   "cudz");      // acc. anim. pl.
    checkOneTerm(a, "cudzích",   "cudz");      // loc. pl.
    checkOneTerm(a, "cudzími",   "cudz");      // ins. pl.
  }

  public void testAdjectivesIII() throws IOException {
    checkOneTerm(a, "otcov",     "otcov");    // m. nom. sg.
    checkOneTerm(a, "otcovho",   "otcovh");   // m. gen. sg.
    checkOneTerm(a, "otcovmu",   "otcovmu");  // m. dat. sg.
    checkOneTerm(a, "otcov",     "otcov");    // m. acc. inam. sg.
    checkOneTerm(a, "otcovho",   "otcovh");   // m. acc. anim. sg.
    checkOneTerm(a, "otcovom",   "otcov");    // m. loc. sg.
    checkOneTerm(a, "otcovým",   "otcov");    // m. ins. sg.
    checkOneTerm(a, "otcovo",    "otcov");    // n. nom. sg.
    checkOneTerm(a, "otcovho",   "otcovh");   // n. gen. sg.
    checkOneTerm(a, "otcovmu",   "otcovmu");  // n. dat. sg.
    checkOneTerm(a, "otcovo",    "otcov");    // n. acc. sg.
    checkOneTerm(a, "otcovom",   "otcov");    // n. loc. sg.
    checkOneTerm(a, "otcovým",   "otcov");    // n. ins. sg.
    checkOneTerm(a, "otcova",    "otcov");    // f. nom. sg.
    checkOneTerm(a, "otcovej",   "otcov");    // f. gen. sg.
    checkOneTerm(a, "otcovej",   "otcov");    // f. dat. sg.
    checkOneTerm(a, "otcovu",    "otcovu");   // f. acc. sg.
    checkOneTerm(a, "otcovej",   "otcov");    // f. loc. sg.
    checkOneTerm(a, "otcovou",   "otcov");    // f. ins. sg.
    checkOneTerm(a, "otcove",    "otcov");    // nom. inam. pl.
    checkOneTerm(a, "otcovi",    "otc");      // nom. anim. pl.
    checkOneTerm(a, "otcových",  "otcov");    // gen. pl.
    checkOneTerm(a, "otcovým",   "otcov");    // dat. pl.
    checkOneTerm(a, "otcove",    "otcov");    // acc. inam. pl.
    checkOneTerm(a, "otcových",  "otcov");    // acc. anim. pl.
    checkOneTerm(a, "otcových",  "otcov");    // loc. pl.
    checkOneTerm(a, "otcovými",  "otcov");    // ins. pl.
  }

  /** 
   * test some high frequency terms from corpora to look for anything crazy 
   */
  public void testHighFrequencyTerms() throws IOException {
    checkOneTerm(a, "a", "a");
    checkOneTerm(a, "sa", "sa");
    checkOneTerm(a, "v", "v");
    checkOneTerm(a, "na", "na");
    checkOneTerm(a, "je", "je");
    checkOneTerm(a, "to", "to");
    checkOneTerm(a, "že", "že");
    checkOneTerm(a, "som", "som");
    checkOneTerm(a, "aj", "aj");
    checkOneTerm(a, "s", "s");
    checkOneTerm(a, "si", "si");
    checkOneTerm(a, "ako", "ako");
    checkOneTerm(a, "z", "z");
    checkOneTerm(a, "do", "do");
    checkOneTerm(a, "o", "o");
    checkOneTerm(a, "ale", "ale");
    checkOneTerm(a, "by", "by");
    checkOneTerm(a, "tak", "tak");
    checkOneTerm(a, "za", "za");
    checkOneTerm(a, "už", "už");
    checkOneTerm(a, "čo", "čo");
    checkOneTerm(a, "po", "po");
    checkOneTerm(a, "pre", "pre");
    checkOneTerm(a, "sme", "sme");
    checkOneTerm(a, "keď", "keď");
    checkOneTerm(a, "len", "len");
    checkOneTerm(a, "nie", "nie");
    checkOneTerm(a, "sú", "sú");
    checkOneTerm(a, "k", "k");
    checkOneTerm(a, "od", "od");
    checkOneTerm(a, "ich", "ich");
    checkOneTerm(a, "ktoré", "ktor");
    checkOneTerm(a, "pri", "pri");
    checkOneTerm(a, "mi", "mi");
    checkOneTerm(a, "alebo", "aleb");
    checkOneTerm(a, "aby", "aby");
    checkOneTerm(a, "či", "či");
    checkOneTerm(a, "však", "však");
    checkOneTerm(a, "ak", "ak");
    checkOneTerm(a, "ktorý", "ktor");
    checkOneTerm(a, "ešte", "esk");
    checkOneTerm(a, "vo", "vo");
    checkOneTerm(a, "ani", "ani");
    checkOneTerm(a, "jeho", "jeh");
    checkOneTerm(a, "bude", "bud");
    checkOneTerm(a, "má", "má");
    checkOneTerm(a, "bol", "bol");
    checkOneTerm(a, "ho", "ho");
    checkOneTerm(a, "ja", "ja");
    checkOneTerm(a, "podľa", "podľ");
    checkOneTerm(a, "až", "až");
    checkOneTerm(a, "bolo", "bol");
    checkOneTerm(a, "jej", "jej");
    checkOneTerm(a, "ma", "ma");
    checkOneTerm(a, "pred", "pred");
    checkOneTerm(a, "no", "no");
    checkOneTerm(a, "byť", "byť");
    checkOneTerm(a, "so", "so");
    checkOneTerm(a, "viac", "viac");
    checkOneTerm(a, "zo", "zo");
    checkOneTerm(a, "tam", "tam");
    checkOneTerm(a, "tom", "tom");
    checkOneTerm(a, "veľmi", "veľ");
    checkOneTerm(a, "bola", "bol");
    checkOneTerm(a, "tu", "tu");
    checkOneTerm(a, "ktorá", "ktor");
    checkOneTerm(a, "kde", "kde");
    checkOneTerm(a, "nás", "nás");
    checkOneTerm(a, "i", "i");
    checkOneTerm(a, "boli", "bol");
    checkOneTerm(a, "potom", "pot");
    checkOneTerm(a, "roku", "roku");
    checkOneTerm(a, "toho", "toh");
    checkOneTerm(a, "asi", "asi");
    checkOneTerm(a, "dnes", "dnes");
    checkOneTerm(a, "ten", "ten");
    checkOneTerm(a, "tým", "tým");
    checkOneTerm(a, "mu", "mu");
    checkOneTerm(a, "bez", "bez");
    checkOneTerm(a, "ju", "ju");
    checkOneTerm(a, "môže", "môh");
    checkOneTerm(a, "ktorí", "ktor");
    checkOneTerm(a, "preto", "pret");
    checkOneTerm(a, "ľudí", "ľud");
    checkOneTerm(a, "všetko", "všetk");
    checkOneTerm(a, "niečo", "nieč");
    checkOneTerm(a, "ste", "ste");
    checkOneTerm(a, "iba", "iba");
    checkOneTerm(a, "možno", "možn");
    checkOneTerm(a, "nám", "nám");
    checkOneTerm(a, "mal", "mal");
    checkOneTerm(a, "d", "d");
    checkOneTerm(a, "lebo", "leb");
    checkOneTerm(a, "teraz", "teraz");
    checkOneTerm(a, "medzi", "medh");
    checkOneTerm(a, "tento", "tent");
    checkOneTerm(a, "nič", "nič");
    checkOneTerm(a, "teda", "ted");
    checkOneTerm(a, "majú", "maj");
    checkOneTerm(a, "mali", "mal");
    checkOneTerm(a, "rokov", "rokov");
    checkOneTerm(a, "tomu", "tomu");
    checkOneTerm(a, "mám", "mám");
    checkOneTerm(a, "ľudia", "ľudi");
    checkOneTerm(a, "pod", "pod");
    checkOneTerm(a, "práve", "práv");
    checkOneTerm(a, "stále", "stál");
    checkOneTerm(a, "svoje", "svoj");
    checkOneTerm(a, "u", "u");
    checkOneTerm(a, "napríklad", "napríklad");
    checkOneTerm(a, "nich", "nich");
    checkOneTerm(a, "tiež", "tiež");
    checkOneTerm(a, "im", "im");
    checkOneTerm(a, "mňa", "mňa");
    checkOneTerm(a, "budú", "bud");
    checkOneTerm(a, "jeden", "jeden");
    checkOneTerm(a, "mať", "mať");
    checkOneTerm(a, "tie", "tie");
    checkOneTerm(a, "vždy", "vžd");
    checkOneTerm(a, "každý", "každ");
    checkOneTerm(a, "pretože", "pretoh");
    checkOneTerm(a, "kto", "kto");
    checkOneTerm(a, "prečo", "preč");
  }

  public void testEmptyTerm() throws IOException {
    Analyzer a = new Analyzer() {
      @Override
      protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new KeywordTokenizer();
        return new TokenStreamComponents(tokenizer, new SlovakLightStemFilter(tokenizer));
      }
    };
    checkOneTerm(a, "", "");
    a.close();
  }
  
}
