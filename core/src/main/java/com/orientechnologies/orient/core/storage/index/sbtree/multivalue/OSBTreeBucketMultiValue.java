/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */

package com.orientechnologies.orient.core.storage.index.sbtree.multivalue;

import com.orientechnologies.common.comparator.ODefaultComparator;
import com.orientechnologies.common.serialization.types.OBinarySerializer;
import com.orientechnologies.common.serialization.types.OByteSerializer;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.serialization.types.OShortSerializer;
import com.orientechnologies.orient.core.encryption.OEncryption;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurablePage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author Andrey Lomakin (a.lomakin-at-orientdb.com)
 * @since 8/7/13
 */
public class OSBTreeBucketMultiValue<K> extends ODurablePage {
  private static final int RID_SIZE = OShortSerializer.SHORT_SIZE + OLongSerializer.LONG_SIZE;

  private static final int FREE_POINTER_OFFSET  = NEXT_FREE_POSITION;
  private static final int SIZE_OFFSET          = FREE_POINTER_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int IS_LEAF_OFFSET       = SIZE_OFFSET + OIntegerSerializer.INT_SIZE;
  private static final int LEFT_SIBLING_OFFSET  = IS_LEAF_OFFSET + OByteSerializer.BYTE_SIZE;
  private static final int RIGHT_SIBLING_OFFSET = LEFT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;

  private static final int TREE_SIZE_OFFSET       = RIGHT_SIBLING_OFFSET + OLongSerializer.LONG_SIZE;
  private static final int POSITIONS_ARRAY_OFFSET = TREE_SIZE_OFFSET + OLongSerializer.LONG_SIZE;

  private final boolean isLeaf;

  private final OBinarySerializer<K> keySerializer;

  private final Comparator<? super K> comparator = ODefaultComparator.INSTANCE;

  private final OEncryption encryption;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  OSBTreeBucketMultiValue(OCacheEntry cacheEntry, boolean isLeaf, OBinarySerializer<K> keySerializer, OEncryption encryption) {
    super(cacheEntry);

    this.isLeaf = isLeaf;
    this.keySerializer = keySerializer;
    this.encryption = encryption;

    setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);
    setIntValue(SIZE_OFFSET, 0);

    setByteValue(IS_LEAF_OFFSET, (byte) (isLeaf ? 1 : 0));
    setLongValue(LEFT_SIBLING_OFFSET, -1);
    setLongValue(RIGHT_SIBLING_OFFSET, -1);

    setLongValue(TREE_SIZE_OFFSET, 0);
  }

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  OSBTreeBucketMultiValue(OCacheEntry cacheEntry, OBinarySerializer<K> keySerializer, OEncryption encryption) {
    super(cacheEntry);
    this.encryption = encryption;

    this.isLeaf = getByteValue(IS_LEAF_OFFSET) > 0;
    this.keySerializer = keySerializer;
  }

  void setTreeSize(long size) {
    setLongValue(TREE_SIZE_OFFSET, size);
  }

  long getTreeSize() {
    return getLongValue(TREE_SIZE_OFFSET);
  }

  boolean isEmpty() {
    return size() == 0;
  }

  int find(K key) {
    int low = 0;
    int high = size() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      K midVal = getKey(mid);
      int cmp = comparator.compare(midVal, key);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // key found
      }
    }

    return -(low + 1); // key not found.
  }

  boolean remove(final int entryIndex, final ORID value) {
    assert isLeaf;

    final int entryPosition = getIntValue(POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE);

    int position = entryPosition;
    int nextItem = getIntValue(position);
    position += OIntegerSerializer.INT_SIZE;

    final int keySize;
    if (encryption == null) {
      keySize = getObjectSizeInDirectMemory(keySerializer, position);
    } else {
      final int encryptedSize = getIntValue(position);
      keySize = encryptedSize + OIntegerSerializer.INT_SIZE;
    }

    position += keySize;

    if (nextItem == -1) {
      final int clusterId = getShortValue(position);
      if (clusterId != value.getClusterId()) {
        return false;
      }

      position += OShortSerializer.SHORT_SIZE;

      final long clusterPosition = getLongValue(position);
      if (clusterPosition == value.getClusterPosition()) {
        int size = getIntValue(SIZE_OFFSET);

        if (entryIndex < size - 1) {
          moveData(POSITIONS_ARRAY_OFFSET + (entryIndex + 1) * OIntegerSerializer.INT_SIZE,
              POSITIONS_ARRAY_OFFSET + entryIndex * OIntegerSerializer.INT_SIZE,
              (size - entryIndex - 1) * OIntegerSerializer.INT_SIZE);
        }

        size--;
        setIntValue(SIZE_OFFSET, size);

        final int freePointer = getIntValue(FREE_POINTER_OFFSET);
        final int entrySize = OIntegerSerializer.INT_SIZE + RID_SIZE + keySize;

        if (size > 0 && entryPosition > freePointer) {
          moveData(freePointer, freePointer + entrySize, entryPosition - freePointer);
        }

        setIntValue(FREE_POINTER_OFFSET, freePointer + entrySize);

        int currentPositionOffset = POSITIONS_ARRAY_OFFSET;

        for (int i = 0; i < size; i++) {
          final int currentEntryPosition = getIntValue(currentPositionOffset);
          if (currentEntryPosition < entryPosition) {
            setIntValue(currentPositionOffset, currentEntryPosition + entrySize);
          }
          currentPositionOffset += OIntegerSerializer.INT_SIZE;
        }

        return true;
      }
    } else {
      int clusterId = getShortValue(position);
      long clusterPosition = getLongValue(position + OShortSerializer.SHORT_SIZE);

      if (clusterId == value.getClusterId() && clusterPosition == value.getClusterPosition()) {
        final int nextNextItem = getIntValue(nextItem);
        final byte[] nextValue = getBinaryValue(nextItem + OIntegerSerializer.INT_SIZE, RID_SIZE);

        setIntValue(entryPosition, nextNextItem);
        setBinaryValue(entryPosition + OIntegerSerializer.INT_SIZE + keySize, nextValue);

        final int freePointer = getIntValue(FREE_POINTER_OFFSET);
        setIntValue(FREE_POINTER_OFFSET, freePointer + OIntegerSerializer.INT_SIZE + RID_SIZE);

        if (nextItem > freePointer) {
          moveData(freePointer, freePointer + OIntegerSerializer.INT_SIZE + RID_SIZE, nextItem - freePointer);
        }

        return true;
      } else {
        int prevItem = entryPosition;

        while (nextItem > 0) {
          final int nextNextItem = getIntValue(nextItem);
          clusterId = getShortValue(nextItem + OIntegerSerializer.INT_SIZE);
          clusterPosition = getLongValue(nextItem + OIntegerSerializer.INT_SIZE + OShortSerializer.SHORT_SIZE);

          if (clusterId == value.getClusterId() && clusterPosition == value.getClusterPosition()) {
            setIntValue(prevItem, nextNextItem);

            final int freePointer = getIntValue(FREE_POINTER_OFFSET);
            setIntValue(FREE_POINTER_OFFSET, freePointer + OIntegerSerializer.INT_SIZE + RID_SIZE);

            if (nextItem > freePointer) {
              moveData(freePointer, freePointer + OIntegerSerializer.INT_SIZE + RID_SIZE, nextItem - freePointer);
            }

            return true;
          }

          nextItem = nextNextItem;
        }
      }
    }

    return false;
  }

  public int size() {
    return getIntValue(SIZE_OFFSET);
  }

  LeafEntry getLeafEntry(int entryIndex) {
    assert isLeaf;

    int entryPosition = getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    byte[] key;
    int nextItem = getIntValue(entryPosition);
    entryPosition += OIntegerSerializer.INT_SIZE;

    if (encryption == null) {
      final int keySize = getObjectSizeInDirectMemory(keySerializer, entryPosition);
      key = getBinaryValue(entryPosition, keySize);

      entryPosition += keySize;
    } else {
      final int encryptionSize = getIntValue(entryPosition);
      key = getBinaryValue(entryPosition, encryptionSize + OIntegerSerializer.INT_SIZE);

      entryPosition += encryptionSize + OIntegerSerializer.INT_SIZE;
    }

    List<ORID> values = new ArrayList<>();

    int clusterId = getShortValue(entryPosition);
    entryPosition += OShortSerializer.SHORT_SIZE;

    long clusterPosition = getLongValue(entryPosition);

    values.add(new ORecordId(clusterId, clusterPosition));

    while (nextItem > 0) {
      int nextNextItem = getIntValue(nextItem);

      clusterId = getShortValue(nextItem + OIntegerSerializer.INT_SIZE);
      clusterPosition = getLongValue(nextItem + OShortSerializer.SHORT_SIZE + OIntegerSerializer.INT_SIZE);

      values.add(new ORecordId(clusterId, clusterPosition));

      nextItem = nextNextItem;
    }

    return new LeafEntry(key, values);
  }

  NonLeafEntry getNonLeafEntry(int entryIndex) {
    assert !isLeaf;

    int entryPosition = getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    int leftChild = getIntValue(entryPosition);
    entryPosition += OIntegerSerializer.INT_SIZE;

    int rightChild = getIntValue(entryPosition);
    entryPosition += OIntegerSerializer.INT_SIZE;

    byte[] key;

    if (encryption == null) {
      final int keySize = getObjectSizeInDirectMemory(keySerializer, entryPosition);
      key = getBinaryValue(entryPosition, keySize);
    } else {
      final int encryptionSize = getIntValue(entryPosition);
      key = getBinaryValue(entryPosition, encryptionSize + OIntegerSerializer.INT_SIZE);
    }

    return new NonLeafEntry(key, leftChild, rightChild);
  }

  int getLeft(int entryIndex) {
    assert !isLeaf;

    final int entryPosition = getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    return getIntValue(entryPosition);
  }

  int getRight(int entryIndex) {
    assert !isLeaf;

    final int entryPosition = getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    return getIntValue(entryPosition + OIntegerSerializer.INT_SIZE);
  }

  /**
   * Obtains the value stored under the given entry index in this bucket.
   *
   * @param entryIndex the value entry index.
   *
   * @return the obtained value.
   */
  List<ORID> getValues(int entryIndex) {
    assert isLeaf;

    int entryPosition = getIntValue(entryIndex * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    int nextItem = getIntValue(entryPosition);
    entryPosition += OIntegerSerializer.INT_SIZE;

    // skip key
    if (encryption == null) {
      entryPosition += getObjectSizeInDirectMemory(keySerializer, entryPosition);
    } else {
      final int encryptedSize = getIntValue(entryPosition);
      entryPosition += OIntegerSerializer.INT_SIZE + encryptedSize;
    }

    int clusterId = getShortValue(entryPosition);
    long clusterPosition = getLongValue(entryPosition + OShortSerializer.SHORT_SIZE);

    final List<ORID> results = new ArrayList<>();
    results.add(new ORecordId(clusterId, clusterPosition));

    while (nextItem > 0) {
      final int nextNextItem = getIntValue(nextItem);

      clusterId = getShortValue(nextItem + OIntegerSerializer.INT_SIZE);
      clusterPosition = getLongValue(nextItem + OIntegerSerializer.INT_SIZE + OShortSerializer.SHORT_SIZE);

      results.add(new ORecordId(clusterId, clusterPosition));

      nextItem = nextNextItem;
    }

    return results;
  }

  public K getKey(int index) {
    int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf) {
      entryPosition += 2 * OIntegerSerializer.INT_SIZE;
    } else {
      entryPosition += OIntegerSerializer.INT_SIZE;
    }

    if (encryption == null) {
      return deserializeFromDirectMemory(keySerializer, entryPosition);
    } else {
      final int encryptedSize = getIntValue(entryPosition);
      entryPosition += OIntegerSerializer.INT_SIZE;

      final byte[] encryptedKey = getBinaryValue(entryPosition, encryptedSize);
      final byte[] serializedKey = encryption.decrypt(encryptedKey);
      return keySerializer.deserializeNativeObject(serializedKey, 0);
    }
  }

  byte[] getRawKey(int index) {
    int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);

    if (!isLeaf) {
      entryPosition += 2 * OIntegerSerializer.INT_SIZE;
    } else {
      entryPosition += OIntegerSerializer.INT_SIZE;
    }

    if (encryption == null) {
      final int keySize = getObjectSizeInDirectMemory(keySerializer, entryPosition);
      return getBinaryValue(entryPosition, keySize);
    } else {
      final int encryptedSize = getIntValue(entryPosition);
      return getBinaryValue(entryPosition, encryptedSize + OIntegerSerializer.INT_SIZE);
    }
  }

  public boolean isLeaf() {
    return isLeaf;
  }

  public void addAll(final List<Entry> entries) {
    if (!isLeaf) {
      for (int i = 0; i < entries.size(); i++) {
        final NonLeafEntry entry = (NonLeafEntry) entries.get(i);
        addNonLeafEntry(i, entry.key, entry.leftChild, entry.rightChild, false);
      }
    } else {
      for (int i = 0; i < entries.size(); i++) {
        final LeafEntry entry = (LeafEntry) entries.get(i);
        final byte[] key = entry.key;
        final List<ORID> values = entry.values;

        addNewLeafEntry(i, key, values.get(0));

        for (int n = 1; n < values.size(); n++) {
          appendNewLeafEntry(i, values.get(n));
        }
      }
    }

    setIntValue(SIZE_OFFSET, entries.size());
  }

  public void shrink(final int newSize) {
    if (isLeaf) {
      final List<LeafEntry> entries = new ArrayList<>(newSize);

      for (int i = 0; i < newSize; i++) {
        entries.add(getLeafEntry(i));
      }

      setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);

      int index = 0;
      for (final LeafEntry entry : entries) {
        final byte[] key = entry.key;
        final List<ORID> values = entry.values;

        addNewLeafEntry(index, key, values.get(0));

        for (int n = 1; n < values.size(); n++) {
          appendNewLeafEntry(index, values.get(n));
        }
        index++;
      }

      setIntValue(SIZE_OFFSET, newSize);
    } else {
      final List<NonLeafEntry> entries = new ArrayList<>(newSize);

      for (int i = 0; i < newSize; i++) {
        entries.add(getNonLeafEntry(i));
      }

      setIntValue(FREE_POINTER_OFFSET, MAX_PAGE_SIZE_BYTES);

      int index = 0;
      for (final NonLeafEntry entry : entries) {
        addNonLeafEntry(index, entry.key, entry.leftChild, entry.rightChild, false);
        index++;
      }

      setIntValue(SIZE_OFFSET, newSize);
    }
  }

  void halfSingleEntry() {
    assert size() == 1;

    final int entryPosition = getIntValue(POSITIONS_ARRAY_OFFSET);
    final List<Integer> items = new ArrayList<>();
    items.add(entryPosition);

    int nextItem = entryPosition;
    while (true) {
      final int nextNextItem = getIntValue(nextItem);
      if (nextNextItem != -1) {
        items.add(nextNextItem);
      } else {
        break;
      }

      nextItem = nextNextItem;
    }

    final int size = items.size();
    final int halfIndex = size / 2;
    final List<Integer> itemsToRemove = items.subList(1, halfIndex + 1);

    final int lastItemPos = items.get(halfIndex);

    final int nextFirsItem = getIntValue(lastItemPos);
    final byte[] firstRid = getBinaryValue(lastItemPos + OIntegerSerializer.INT_SIZE, RID_SIZE);

    int freePointer = getIntValue(FREE_POINTER_OFFSET);

    for (int itemPos : itemsToRemove) {
      if (itemPos > freePointer) {
        moveData(freePointer, freePointer + OIntegerSerializer.INT_SIZE + RID_SIZE, nextItem - freePointer);
      }

      freePointer += OIntegerSerializer.INT_SIZE + RID_SIZE;
    }

    setIntValue(FREE_POINTER_OFFSET, freePointer);

    setIntValue(entryPosition, nextFirsItem);

    final int keySize;
    if (encryption == null) {
      keySize = getObjectSizeInDirectMemory(keySerializer, entryPosition + OIntegerSerializer.INT_SIZE);
    } else {
      final int encryptedSize = getIntValue(entryPosition + OIntegerSerializer.INT_SIZE);
      keySize = OIntegerSerializer.INT_SIZE + encryptedSize;
    }

    setBinaryValue(entryPosition + OIntegerSerializer.INT_SIZE + keySize, firstRid);
  }

  boolean addNewLeafEntry(final int index, final byte[] serializedKey, final ORID value) {
    assert isLeaf;

    final int entrySize = serializedKey.length + RID_SIZE + OIntegerSerializer.INT_SIZE; //next item pointer at the begging of entry
    final int size = getIntValue(SIZE_OFFSET);

    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize < (size + 1) * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE, (size - index) * OIntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    freePointer += setIntValue(freePointer, -1); //next item pointer
    freePointer += setBinaryValue(freePointer, serializedKey);//key
    freePointer += setShortValue(freePointer, (short) value.getClusterId());//rid
    setLongValue(freePointer, value.getClusterPosition());

    return true;
  }

  boolean appendNewLeafEntry(final int index, final ORID value) {
    assert isLeaf;

    final int itemSize = OIntegerSerializer.INT_SIZE + RID_SIZE;//next item pointer + RID
    int freePointer = getIntValue(FREE_POINTER_OFFSET);

    final int size = getIntValue(SIZE_OFFSET);

    if (freePointer - itemSize < size * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET + itemSize) {
      return false;
    }

    freePointer -= itemSize;
    setIntValue(FREE_POINTER_OFFSET, freePointer);

    final int entryPosition = getIntValue(index * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET);
    final int nextItem = getIntValue(entryPosition);

    setIntValue(entryPosition, freePointer);//update list header

    freePointer += setIntValue(freePointer, nextItem);//next item pointer
    freePointer += setShortValue(freePointer, (short) value.getClusterId());//rid
    setLongValue(freePointer, value.getClusterPosition());

    return true;
  }

  boolean addNonLeafEntry(int index, byte[] serializedKey, int leftChild, int rightChild, boolean updateNeighbors) {
    assert !isLeaf;

    int entrySize = serializedKey.length + 2 * OIntegerSerializer.INT_SIZE;

    int size = size();
    int freePointer = getIntValue(FREE_POINTER_OFFSET);
    if (freePointer - entrySize < (size + 1) * OIntegerSerializer.INT_SIZE + POSITIONS_ARRAY_OFFSET) {
      return false;
    }

    if (index <= size - 1) {
      moveData(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE,
          POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE, (size - index) * OIntegerSerializer.INT_SIZE);
    }

    freePointer -= entrySize;

    setIntValue(FREE_POINTER_OFFSET, freePointer);
    setIntValue(POSITIONS_ARRAY_OFFSET + index * OIntegerSerializer.INT_SIZE, freePointer);
    setIntValue(SIZE_OFFSET, size + 1);

    freePointer += setIntValue(freePointer, leftChild);
    freePointer += setIntValue(freePointer, rightChild);

    setBinaryValue(freePointer, serializedKey);

    size++;

    if (updateNeighbors && size > 1) {
      if (index < size - 1) {
        final int nextEntryPosition = getIntValue(POSITIONS_ARRAY_OFFSET + (index + 1) * OIntegerSerializer.INT_SIZE);
        setIntValue(nextEntryPosition, rightChild);
      }

      if (index > 0) {
        final int prevEntryPosition = getIntValue(POSITIONS_ARRAY_OFFSET + (index - 1) * OIntegerSerializer.INT_SIZE);
        setIntValue(prevEntryPosition + OIntegerSerializer.INT_SIZE, leftChild);
      }
    }

    return true;
  }

  void setLeftSibling(long pageIndex) {
    setLongValue(LEFT_SIBLING_OFFSET, pageIndex);
  }

  public long getLeftSibling() {
    return getLongValue(LEFT_SIBLING_OFFSET);
  }

  void setRightSibling(long pageIndex) {
    setLongValue(RIGHT_SIBLING_OFFSET, pageIndex);
  }

  public long getRightSibling() {
    return getLongValue(RIGHT_SIBLING_OFFSET);
  }

  static class Entry {
    final byte[] key;

    public Entry(byte[] key) {
      this.key = key;
    }
  }

  static final class LeafEntry extends Entry {
    final List<ORID> values;

    LeafEntry(byte[] key, List<ORID> values) {
      super(key);
      this.values = values;
    }
  }

  static final class NonLeafEntry extends Entry {
    final int leftChild;
    final int rightChild;

    NonLeafEntry(byte[] key, int leftChild, int rightChild) {
      super(key);

      this.leftChild = leftChild;
      this.rightChild = rightChild;
    }
  }
}
