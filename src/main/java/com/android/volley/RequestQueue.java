/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request dispatch queue with a thread pool of dispatchers.
 * <p>
 * Request分发队列（包含一个分发线程池）
 * <p>
 * Calling {@link #add(Request)} will enqueue the given Request for dispatch,
 * resolving from either cache or network on a worker thread, and then delivering
 * a parsed response on the main thread.
 * <p>
 * 1. 调用 {@link #add(Request)} ，将一个Request对象加入RequestQueue。
 * 2. 在Cache或者Network线程中处理该Request
 * 3. 分发Response至UI线程
 */
public class RequestQueue {

    /**
     * Callback interface for completed requests.
     * <p>
     * 完成请求时回调该接口
     */
    public static interface RequestFinishedListener<T> {
        /**
         * Called when a request has finished processing.
         */
        public void onRequestFinished(Request<T> request);
    }

    /**
     * Used for generating monotonically-increasing sequence numbers for requests.
     */
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     * <p>
     * 将正在执行的Request暂存
     * <p>
     * <ul>
     * <li>containsKey(cacheKey) indicates that there is a request in flight for the given cache
     * key.</li>
     * <li>get(cacheKey) returns waiting requests for the given cache key. The in flight request
     * is <em>not</em> contained in that list. Is null if no requests are staged.</li>
     * </ul>
     * <p>
     * 1. 如果包含该Key（containsKey(cacheKey) == true），说明当前Request正在执行
     * 2. get(cacheKey)返回该cacheKey对应的Request队列
     */
    private final Map<String, Queue<Request<?>>> mWaitingRequests =
            new HashMap<String, Queue<Request<?>>>();

    /**
     * The set of all requests currently being processed by this RequestQueue. A Request
     * will be in this set if it is waiting in any queue or currently being processed by
     * any dispatcher.
     * <p>
     * 存储所有当前正在处理的Request
     */
    private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

    /**
     * The cache triage queue.
     */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue =
            new PriorityBlockingQueue<Request<?>>();

    /**
     * The queue of requests that are actually going out to the network.
     */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
            new PriorityBlockingQueue<Request<?>>();

    /**
     * Number of network request dispatcher threads to start.
     */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4; //网络请求线程池默认大小为4

    /**
     * Cache interface for retrieving and storing responses.
     */
    private final Cache mCache; // Response缓存

    /**
     * Network interface for performing requests.
     */
    private final Network mNetwork;

    /**
     * Response delivery mechanism.
     */
    private final ResponseDelivery mDelivery; // Response分发机制

    /**
     * The network dispatchers.
     */
    private NetworkDispatcher[] mDispatchers;

    /**
     * The cache dispatcher.
     */
    private CacheDispatcher mCacheDispatcher;

    private List<RequestFinishedListener> mFinishedListeners =
            new ArrayList<RequestFinishedListener>();

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     * <p>
     * 创建RequestQueue。 当{@link #start()}被调用时，才会开始处理Request.
     *
     * @param cache          A Cache to use for persisting responses to disk. 缓存Response至磁盘
     * @param network        A Network interface for performing HTTP requests. 执行HTTPRequest
     * @param threadPoolSize Number of network dispatcher threads to create.  Network Dispatcher 个数
     * @param delivery       A ResponseDelivery interface for posting responses and errors. Response分发机制
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize,
                        ResponseDelivery delivery) {
        mCache = cache;
        mNetwork = network;
        mDispatchers = new NetworkDispatcher[threadPoolSize];
        mDelivery = delivery;
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache          A Cache to use for persisting responses to disk
     * @param network        A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     *                       <p>
     *                       默认构造一个ExecutorDelivery作为Response分发机制
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize) {
        this(cache, network, threadPoolSize,
                new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache   A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     */
    public RequestQueue(Cache cache, Network network) {
        this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
    }

    /**
     * Starts the dispatchers in this queue.
     * <p>
     * 开启当前的RequestQueue.
     */
    public void start() {
        stop();  // Make sure any currently running dispatchers are stopped.

        // Create the cache dispatcher and start it.
        // 启动Cache Dispatcher
        mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();

        // Create network dispatchers (and corresponding threads) up to the pool size.
        // 穿件NetworkDispatcher并启动
        for (int i = 0; i < mDispatchers.length; i++) {
            NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
                    mCache, mDelivery);
            mDispatchers[i] = networkDispatcher;
            networkDispatcher.start();
        }
    }

    /**
     * Stops the cache and network dispatchers.
     * <p>
     * 关闭当前的Dispatcher（包括缓存和网络）
     */
    public void stop() {
        if (mCacheDispatcher != null) {
            mCacheDispatcher.quit();
        }
        for (int i = 0; i < mDispatchers.length; i++) {
            if (mDispatchers[i] != null) {
                mDispatchers[i].quit();
            }
        }
    }

    /**
     * Gets a sequence number.
     */
    public int getSequenceNumber() {
        return mSequenceGenerator.incrementAndGet();
    }

    /**
     * Gets the {@link Cache} instance being used.
     */
    public Cache getCache() {
        return mCache;
    }

    /**
     * A simple predicate or filter interface for Requests, for use by
     * {@link RequestQueue#cancelAll(RequestFilter)}.
     * <p>
     * Request过滤器，当执行{@link RequestQueue#cancelAll(RequestFilter)}时，根据{@link #apply(Request)}的返回值判断是否要cancel
     */
    public interface RequestFilter {
        public boolean apply(Request<?> request);
    }

    /**
     * Cancels all requests in this queue for which the given filter applies.
     * <p>
     * 取消所有符合filter条件的Request
     *
     * @param filter The filtering function to use
     */
    public void cancelAll(RequestFilter filter) {
        synchronized (mCurrentRequests) {
            for (Request<?> request : mCurrentRequests) {
                if (filter.apply(request)) {
                    request.cancel();
                }
            }
        }
    }

    /**
     * Cancels all requests in this queue with the given tag. Tag must be non-null
     * and equality is by identity.
     * <p>
     * 取消所有等于当前tag的Request
     */
    public void cancelAll(final Object tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Cannot cancelAll with a null tag");
        }
        cancelAll(new RequestFilter() {
            @Override
            public boolean apply(Request<?> request) {
                return request.getTag() == tag;
            }
        });
    }

    /**
     * Adds a Request to the dispatch queue.
     *
     * @param request The request to service
     * @return The passed-in request
     */
    public <T> Request<T> add(Request<T> request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        request.setRequestQueue(this);
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request); //将请求添加到当前Request集合（Set）中
        }

        // Process requests in the order they are added.
        request.setSequence(getSequenceNumber()); //设置序列号
        request.addMarker("add-to-queue");

        // If the request is uncacheable, skip the cache queue and go straight to the network.
        // 如果该Request不允许缓存，则直接添加到网络请求队列
        if (!request.shouldCache()) {
            mNetworkQueue.add(request);
            return request;
        }

        // Insert request into stage if there's already a request with the same cache key in flight.
        // 将请求插入到等待列表
        synchronized (mWaitingRequests) {
            String cacheKey = request.getCacheKey();
            if (mWaitingRequests.containsKey(cacheKey)) {
                // There is already a request in flight. Queue up.
                Queue<Request<?>> stagedRequests = mWaitingRequests.get(cacheKey);
                if (stagedRequests == null) {
                    stagedRequests = new LinkedList<Request<?>>();
                }
                stagedRequests.add(request);
                mWaitingRequests.put(cacheKey, stagedRequests);
                if (VolleyLog.DEBUG) {
                    VolleyLog.v("Request for cacheKey=%s is in flight, putting on hold.", cacheKey);
                }
            } else {
                // Insert 'null' queue for this cacheKey, indicating there is now a request in
                // flight.
                // 如果不包含该key，则添加一个null至等待队列，表示有一个请求正在执行。
                mWaitingRequests.put(cacheKey, null);
                mCacheQueue.add(request);
            }
            return request;
        }
    }

    /**
     * Called from {@link Request#finish(String)}, indicating that processing of the given request
     * has finished.
     * <p>
     * 该方法会在{@link Request#finish(String)}执行时被调用，表明当前的Request已经执行结束
     * <p>
     * <p>
     * <p>Releases waiting requests for <code>request.getCacheKey()</code> if
     * <code>request.shouldCache()</code>.</p>
     */
    <T> void finish(Request<T> request) {
        // Remove from the set of requests currently being processed
        // 从当前请求队列中删除
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }
        synchronized (mFinishedListeners) {
            //回调 onRequestFinished()
            for (RequestFinishedListener<T> listener : mFinishedListeners) {
                listener.onRequestFinished(request);
            }
        }

        if (request.shouldCache()) {
            synchronized (mWaitingRequests) {
                String cacheKey = request.getCacheKey();
                Queue<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
                if (waitingRequests != null) {
                    if (VolleyLog.DEBUG) {
                        VolleyLog.v("Releasing %d waiting requests for cacheKey=%s.",
                                waitingRequests.size(), cacheKey);
                    }
                    // Process all queued up requests. They won't be considered as in flight, but
                    // that's not a problem as the cache has been primed by 'request'.
                    // 处理所有正在排队的请求。
                    mCacheQueue.addAll(waitingRequests);
                }
            }
        }
    }

    public <T> void addRequestFinishedListener(RequestFinishedListener<T> listener) {
        synchronized (mFinishedListeners) {
            mFinishedListeners.add(listener);
        }
    }

    /**
     * Remove a RequestFinishedListener. Has no effect if listener was not previously added.
     */
    public <T> void removeRequestFinishedListener(RequestFinishedListener<T> listener) {
        synchronized (mFinishedListeners) {
            mFinishedListeners.remove(listener);
        }
    }
}
