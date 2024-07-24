/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services.sqs.internal.batchmanager.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import software.amazon.awssdk.annotations.SdkInternalApi;

@SdkInternalApi
public final class RequestBatchBuffer<RequestT, ResponseT> implements BatchBuffer<RequestT, ResponseT>  {
    private final Object flushLock = new Object();

    private final Map<String, BatchingExecutionContext<RequestT, ResponseT>> idToBatchContext;

    /**
     * Maximum number of elements that can be included in the BatchBuffer.
     */
    private final int maxBufferSize;

    /**
     * Batch entries in a batch request require a unique ID so nextId keeps track of the ID to assign to the next
     * BatchingExecutionContext. For simplicity, the ID is just an integer that is incremented everytime a new request and
     * response pair is received.
     */
    private int nextId;

    /**
     * Keeps track of the ID of the next entry to be added in a batch request. This ID does not necessarily correlate to a
     * request that already exists in the idToBatchContext map since it refers to the next entry (ex. if the last entry added
     * to idToBatchContext had an id of 22, nextBatchEntry will have a value of 23).
     */
    private int nextBatchEntry;

    /**
     * The scheduled flush tasks associated with this batchBuffer.
     */
    private ScheduledFuture<?> scheduledFlush;

    public RequestBatchBuffer(int maxBufferSize, ScheduledFuture<?> scheduledFlush) {
        this.idToBatchContext = new ConcurrentHashMap<>();
        this.maxBufferSize = maxBufferSize;
        this.nextId = 0;
        this.nextBatchEntry = 0;
        this.scheduledFlush = scheduledFlush;
    }

    public Map<String, BatchingExecutionContext<RequestT, ResponseT>> flushableRequests(int maxBatchItems) {
        synchronized (flushLock) {
            if (idToBatchContext.size() >= maxBatchItems) {
                return extractFlushedEntries(maxBatchItems);
            }
            return new ConcurrentHashMap<>();
        }
    }

    public Map<String, BatchingExecutionContext<RequestT, ResponseT>> flushableScheduledRequests(int maxBatchItems) {
        synchronized (flushLock) {
            if (idToBatchContext.size() > 0) {
                return extractFlushedEntries(maxBatchItems);
            }
            return new ConcurrentHashMap<>();
        }
    }

    private Map<String, BatchingExecutionContext<RequestT, ResponseT>> extractFlushedEntries(int maxBatchItems) {
        LinkedHashMap<String, BatchingExecutionContext<RequestT, ResponseT>> requestEntries = new LinkedHashMap<>();
        String nextEntry;
        while (requestEntries.size() < maxBatchItems && hasNextBatchEntry()) {
            nextEntry = nextBatchEntry();
            requestEntries.put(nextEntry, idToBatchContext.get(nextEntry));
            idToBatchContext.remove(nextEntry);
        }
        return requestEntries;
    }

    public void put(RequestT request, CompletableFuture<ResponseT> response) {
        synchronized (this) {
            if (idToBatchContext.size() == maxBufferSize) {
                throw new IllegalStateException("Reached MaxBufferSize of: " + maxBufferSize);
            }

            if (nextId == Integer.MAX_VALUE) {
                nextId = 0;
            }
            String id = Integer.toString(nextId++);
            idToBatchContext.put(id, new BatchingExecutionContext<>(request, response));
        }
    }

    private boolean hasNextBatchEntry() {
        return idToBatchContext.containsKey(Integer.toString(nextBatchEntry));
    }

    private String nextBatchEntry() {
        if (nextBatchEntry == Integer.MAX_VALUE) {
            nextBatchEntry = 0;
        }
        return Integer.toString(nextBatchEntry++);
    }

    public void putScheduledFlush(ScheduledFuture<?> scheduledFlush) {
        this.scheduledFlush = scheduledFlush;
    }

    public void cancelScheduledFlush() {
        scheduledFlush.cancel(false);
    }

    public Collection<CompletableFuture<ResponseT>> responses() {
        return idToBatchContext.values()
                               .stream()
                               .map(BatchingExecutionContext::response)
                               .collect(Collectors.toList());
    }

    public void clear() {
        idToBatchContext.clear();
    }
}
