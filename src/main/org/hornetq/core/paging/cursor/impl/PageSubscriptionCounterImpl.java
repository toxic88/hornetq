/*
 * Copyright 2010 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.paging.cursor.impl;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import org.hornetq.api.core.Pair;
import org.hornetq.core.logging.Logger;
import org.hornetq.core.paging.cursor.PageSubscriptionCounter;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.server.MessageReference;
import org.hornetq.core.transaction.Transaction;
import org.hornetq.core.transaction.TransactionOperation;
import org.hornetq.core.transaction.TransactionPropertyIndexes;

/**
 * This class will encapsulate the persistent counters for the PagingSubscription
 *
 * @author clebertsuconic
 *
 *
 */
public class PageSubscriptionCounterImpl implements PageSubscriptionCounter
{

   // Constants -----------------------------------------------------
   static final Logger log = Logger.getLogger(PageSubscriptionCounterImpl.class);

   // Attributes ----------------------------------------------------

   private static final int FLUSH_COUNTER = 1000;

   private final long subscriptionID;

   // the journal record id that is holding the current value
   private long recordID = -1;

   private final boolean persistent;

   private final StorageManager storage;

   private final AtomicLong value = new AtomicLong(0);

   private final LinkedList<Long> incrementRecords = new LinkedList<Long>();

   private LinkedList<Pair<Long, Integer>> loadList;

   private final Executor executor;

   private final Runnable cleanupCheck = new Runnable()
   {
      public void run()
      {
         cleanup();
      }
   };

   // protected LinkedList

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   public PageSubscriptionCounterImpl(final StorageManager storage,
                                      final boolean persistent,
                                      final long subscriptionID,
                                      final Executor executor)
   {
      this.subscriptionID = subscriptionID;
      this.storage = storage;
      this.executor = executor;
      this.persistent = persistent;
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.impl.PagingSubscriptionCounterInterface#getValue()
    */
   public long getValue()
   {
      return value.get();
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.impl.PagingSubscriptionCounterInterface#increment(org.hornetq.core.transaction.Transaction, int)
    */
   public void increment(Transaction tx, int add) throws Exception
   {
      tx.setContainsPersistent();

      if (!persistent)
      {
         replayIncrement(tx, -1, add);
      }
      else
      {
         long id = storage.storePageCounterInc(tx.getID(), this.subscriptionID, add);

         replayIncrement(tx, id, add);
      }

   }

   /**
    * This method will install the prepared TXs
    * @param tx
    * @param recordID
    * @param add
    */
   public void replayIncrement(Transaction tx, long recordID, int add)
   {
      CounterOperations oper = (CounterOperations)tx.getProperty(TransactionPropertyIndexes.PAGE_COUNT_INC);

      if (oper == null)
      {
         oper = new CounterOperations();
         tx.putProperty(TransactionPropertyIndexes.PAGE_COUNT_INC, oper);
         tx.addOperation(oper);
      }

      oper.operations.add(new ItemOper(this, recordID, add));
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.impl.PagingSubscriptionCounterInterface#loadValue(long, long)
    */
   public synchronized void loadValue(final long recordID, final long value)
   {
      this.value.set(value);
      this.recordID = recordID;
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.impl.PagingSubscriptionCounterInterface#incrementProcessed(long, int)
    */
   public synchronized void incrementProcessed(long id, int add)
   {
      addInc(id, add);
      if (incrementRecords.size() > FLUSH_COUNTER)
      {
         executor.execute(cleanupCheck);
      }

   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.PageSubscriptionCounter#loadInc(long, int)
    */
   public void loadInc(long id, int add)
   {
      if (loadList == null)
      {
         loadList = new LinkedList<Pair<Long, Integer>>();
      }

      loadList.add(new Pair<Long, Integer>(id, add));
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.PageSubscriptionCounter#processReload()
    */
   public void processReload()
   {
      if (loadList != null)
      {
         for (Pair<Long, Integer> incElement : loadList)
         {
            value.addAndGet(incElement.b);
            incrementRecords.add(incElement.a);
         }
         loadList.clear();
         loadList = null;
      }
   }

   /* (non-Javadoc)
    * @see org.hornetq.core.paging.cursor.impl.PagingSubscriptionCounterInterface#addInc(long, int)
    */
   public void addInc(long id, int variance)
   {
      value.addAndGet(variance);
      
      if (id >= 0)
      {
         incrementRecords.add(id);
      }
   }

   /** This method sould alwas be called from a single threaded executor */
   protected void cleanup()
   {
      ArrayList<Long> deleteList;

      long valueReplace;
      synchronized (this)
      {
         valueReplace = value.get();
         deleteList = new ArrayList<Long>(incrementRecords.size());
         deleteList.addAll(incrementRecords);
         incrementRecords.clear();
      }

      long newRecordID = -1;

      long txCleanup = storage.generateUniqueID();

      try
      {
         for (Long value : deleteList)
         {
            storage.deleteIncrementRecord(txCleanup, value);
         }

         if (recordID >= 0)
         {
            storage.deletePageCounter(txCleanup, recordID);
         }

         newRecordID = storage.storePageCounter(txCleanup, subscriptionID, valueReplace);

         storage.commit(txCleanup);

         storage.waitOnOperations();
      }
      catch (Exception e)
      {
         newRecordID = recordID;

         log.warn(e.getMessage(), e);
         try
         {
            storage.rollback(txCleanup);
         }
         catch (Exception ignored)
         {
         }
      }
      finally
      {
         recordID = newRecordID;
      }
   }

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

   static class ItemOper
   {

      public ItemOper(PageSubscriptionCounterImpl counter, long id, int add)
      {
         this.counter = counter;
         this.id = id;
         this.ammount = add;
      }

      PageSubscriptionCounterImpl counter;

      long id;

      int ammount;
   }

   static class CounterOperations implements TransactionOperation
   {
      LinkedList<ItemOper> operations = new LinkedList<ItemOper>();

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#beforePrepare(org.hornetq.core.transaction.Transaction)
       */
      public void beforePrepare(Transaction tx) throws Exception
      {
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#afterPrepare(org.hornetq.core.transaction.Transaction)
       */
      public void afterPrepare(Transaction tx)
      {
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#beforeCommit(org.hornetq.core.transaction.Transaction)
       */
      public void beforeCommit(Transaction tx) throws Exception
      {
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#afterCommit(org.hornetq.core.transaction.Transaction)
       */
      public void afterCommit(Transaction tx)
      {
         for (ItemOper oper : operations)
         {
            oper.counter.incrementProcessed(oper.id, oper.ammount);
         }
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#beforeRollback(org.hornetq.core.transaction.Transaction)
       */
      public void beforeRollback(Transaction tx) throws Exception
      {
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#afterRollback(org.hornetq.core.transaction.Transaction)
       */
      public void afterRollback(Transaction tx)
      {
      }

      /* (non-Javadoc)
       * @see org.hornetq.core.transaction.TransactionOperation#getRelatedMessageReferences()
       */
      public List<MessageReference> getRelatedMessageReferences()
      {
         return null;
      }
   }

}
