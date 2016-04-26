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

import static org.apache.lucene.analysis.util.StemmerUtil.*;

/**
 * Light Stemmer for Slovak.
 * <p>
 * It uses the algorithm presented here: https://github.com/mrshu/stemm-sk
 * This is a modification of the algorithm described in:  
 * <i>
 * Indexing and stemming approaches for the Czech language
 * </i>
 * http://portal.acm.org/citation.cfm?id=1598600
 * </p>
 */
public class SlovakLightStemmer {
  
  /**
   * Stem an input buffer of Slovak text.
   * 
   * @param s input buffer
   * @param len length of input buffer
   * @return length of input buffer after normalization
   * 
   * <p><b>NOTE</b>: Input is expected to be in lowercase, 
   * but with diacritical marks</p>
   */
  public int stem(char s[], int len) {
    len = removeCase(s, len);
    len = removePossessives(s, len);
    return len;
  }
  
  private int removeCase(char s[], int len) {  
    if (len > 7 && endsWith(s, len, "atoch"))
      return len - 5;
    
    if (len > 6 && endsWith(s, len, "aťom")) {
      return palatalize(s, len - 3);
    }
        
    if (len > 5) { 
      if (endsWith(s, len, "och") ||
          endsWith(s, len, "ich") ||
          endsWith(s, len, "ích") ||
          endsWith(s, len, "ého") ||
          endsWith(s, len, "ami") ||
          endsWith(s, len, "emi") ||
          endsWith(s, len, "ému") ||
          endsWith(s, len, "ete") ||
          endsWith(s, len, "eti") ||
          endsWith(s, len, "iho") ||
          endsWith(s, len, "ího") ||
          endsWith(s, len, "ími") ||
          endsWith(s, len, "imu") ||
          endsWith(s, len, "aťa")) {
        return palatalize(s, len - 2);
      }
      if (endsWith(s, len, "ách") ||
          endsWith(s, len, "ata") ||
          endsWith(s, len, "aty") ||
          endsWith(s, len, "ých") ||
          endsWith(s, len, "ami") ||
          endsWith(s, len, "ové") ||
          endsWith(s, len, "ovi") ||
          endsWith(s, len, "ými")) {
        return len - 3;
      }
    }
    
    if (len > 4) {
      if (endsWith(s, len, "om")) {
        return palatalize(s, len - 1);
      }
      if (endsWith(s, len, "es") ||
          endsWith(s, len, "ém") ||
          endsWith(s, len, "ím")) {
        return palatalize(s, len - 2);
      }
      if (endsWith(s, len, "úm") ||
          endsWith(s, len, "at") ||
          endsWith(s, len, "ám") ||
          endsWith(s, len, "os") ||
          endsWith(s, len, "us") ||
          endsWith(s, len, "ým") ||
          endsWith(s, len, "mi") ||
          endsWith(s, len, "ou") ||
          endsWith(s, len, "ej")) {
        return len - 2;
      }
    }
    
    if (len > 3) {
      switch (s[len - 1]) {
        case 'e':
        case 'i':
        case 'í':
          return palatalize(s, len);
        case 'ú':
        case 'y':
        case 'a':
        case 'o':
        case 'á':
        case 'é':
        case 'ý':
          return len - 1;
      }
    }
    
    return len;
  }
  
  private int removePossessives(char s[], int len) {
    if (len > 5) {
      if (endsWith(s, len, "ov")) {
        return len - 2;
      }
      if (endsWith(s, len, "in")) {
        return palatalize(s, len - 1);
      }
    }

    return len;
  }
  
  private int palatalize(char s[], int len) {
    if (endsWith(s, len, "ci") ||
        endsWith(s, len, "ce") ||
        endsWith(s, len, "či") ||
        endsWith(s, len, "če")) {
      s[len - 2] = 'k';
      return len - 1;
    }

    if (endsWith(s, len, "zi") ||
        endsWith(s, len, "ze") ||
        endsWith(s, len, "ži") ||
        endsWith(s, len, "že")) {
      s[len - 2] = 'h';
      return len - 1;
    }
    
    if (endsWith(s, len, "čte") ||
        endsWith(s, len, "čti") ||
        endsWith(s, len, "čtí")) {
      s[len - 3] = 'c';
      s[len - 2] = 'k';
      return len - 1;
    }

    if (endsWith(s, len, "šte") ||
        endsWith(s, len, "šti") ||
        endsWith(s, len, "ští")) {
      s[len - 3] = 's';
      s[len - 2] = 'k';
      return len - 1;
    }

    return len - 1;
  }
}
