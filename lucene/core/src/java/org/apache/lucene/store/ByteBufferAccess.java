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
package org.apache.lucene.store;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
import java.nio.ByteBuffer;

final class ByteBufferAccess {
  private final String resourceDescription;
  private final BufferCleaner cleaner;
  private final SwitchPoint switchPoint;
  private final MethodHandle mhGetBytesSafe, mhGetByteSafe;
  
  /**
   * Pass in an implementation of this interface to cleanup ByteBuffers.
   * MMapDirectory implements this to allow unmapping of bytebuffers with private Java APIs.
   */
  @FunctionalInterface
  static interface BufferCleaner {
    void freeBuffer(String resourceDescription, ByteBuffer b) throws IOException;
  }
  
  ByteBufferAccess(String resourceDescription, BufferCleaner cleaner) {
    this.resourceDescription = resourceDescription;
    this.cleaner = cleaner;
    if (cleaner != null) {
      switchPoint = new SwitchPoint();
      mhGetBytesSafe = switchPoint.guardWithTest(BYTEBUFFER_GET_BYTES_UNSAFE, BYTEBUFFER_GET_BYTES_FALLBACK);
      mhGetByteSafe = BYTEBUFFER_GET_BYTE_UNSAFE; // xxx
    } else {
      switchPoint = null;
      mhGetBytesSafe = BYTEBUFFER_GET_BYTES_UNSAFE;
      mhGetByteSafe = BYTEBUFFER_GET_BYTE_UNSAFE;
    }
  }
  
  void invalidate(ByteBuffer bufs[]) throws IOException {
    if (cleaner != null) {
      // TODO: we should really batch this via deletePendingFiles() or similar, or perhaps
      // queue up and take care asynchronously from another thread.
      SwitchPoint.invalidateAll(new SwitchPoint[] { switchPoint });
      for (ByteBuffer b : bufs) {
        cleaner.freeBuffer(resourceDescription, b);
      }
    }
  }
  
  ByteBuffer getBytes(ByteBuffer receiver, byte[] dst, int offset, int length) {
    try {
      return (ByteBuffer) mhGetBytesSafe.invokeExact(receiver, dst, offset, length);
    } catch (Throwable e) {
      rethrow(e);
      throw new AssertionError();
    }
  }
  
  byte getByte(ByteBuffer receiver) {
    try {
      return (byte) mhGetByteSafe.invokeExact(receiver);
    } catch (Throwable e) {
      rethrow(e);
      throw new AssertionError();
    }
  }
  
  /** Hack to rethrow unknown Exceptions from {@link MethodHandle#invokeExact}: */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void rethrow(Throwable t) throws T {
      throw (T) t;
  }

  private static MethodHandle createFallback(MethodType type) {
    MethodHandle fallback = MethodHandles.throwException(type.returnType(), NullPointerException.class).bindTo(new NullPointerException());
    return MethodHandles.dropArguments(fallback, 0, type.parameterArray());
  }
  
  private static final MethodHandle BYTEBUFFER_GET_BYTES_UNSAFE, BYTEBUFFER_GET_BYTES_FALLBACK,
    BYTEBUFFER_GET_BYTE_UNSAFE, BYTEBUFFER_GET_BYTE_FALLBACK
    ;
  static {
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    try {
      BYTEBUFFER_GET_BYTES_UNSAFE = lookup.findVirtual(ByteBuffer.class, "get", MethodType.methodType(ByteBuffer.class, byte[].class, int.class, int.class));
      BYTEBUFFER_GET_BYTES_FALLBACK = createFallback(BYTEBUFFER_GET_BYTES_UNSAFE.type());
      BYTEBUFFER_GET_BYTE_UNSAFE = lookup.findVirtual(ByteBuffer.class, "get", MethodType.methodType(byte.class));
      BYTEBUFFER_GET_BYTE_FALLBACK = createFallback(BYTEBUFFER_GET_BYTE_UNSAFE.type());
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new Error(e);
    }
  }
  
}
