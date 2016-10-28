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

package com.android.volley.toolbox;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.widget.ImageView.ScaleType;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyLog;

/**
 * A canned request for getting an image at a given URL and calling
 * back with a decoded Bitmap.
 *
 * 获取Image，Decode生成Bitmap
 */
public class ImageRequest extends Request<Bitmap> {
    /** Socket timeout in milliseconds for image requests */
    public static final int DEFAULT_IMAGE_TIMEOUT_MS = 1000;

    /** Default number of retries for image requests */
    /** 默认重传次数为2 */
    public static final int DEFAULT_IMAGE_MAX_RETRIES = 2;

    /** Default backoff multiplier for image requests */
    /** 重传补偿时间倍数 */
    public static final float DEFAULT_IMAGE_BACKOFF_MULT = 2f;

    private final Response.Listener<Bitmap> mListener;
    private final Config mDecodeConfig; //Bitmap Decode Config
    private final int mMaxWidth;
    private final int mMaxHeight;
    private ScaleType mScaleType;

    /** Decoding lock so that we don't decode more than one image at a time (to avoid OOM's) */
    /** 解码锁, 避免OOM */
    private static final Object sDecodeLock = new Object();

    /**
     * Creates a new image request, decoding to a maximum specified width and
     * height. If both width and height are zero, the image will be decoded to
     * its natural size. If one of the two is nonzero, that dimension will be
     * clamped and the other one will be set to preserve the image's aspect
     * ratio. If both width and height are nonzero, the image will be decoded to
     * be fit in the rectangle of dimensions width x height while keeping its
     * aspect ratio.
     *
     * 1. 如果宽高均为0，则图片将按照其原始的宽高
     * 2. 如果指定的宽度或者高度有一个不为0，则基于此数值按比例缩放
     * 3. 如果均不为0，则图片将会按照所给宽高进行缩放
     *
     * @param url URL of the image
     * @param listener Listener to receive the decoded bitmap
     * @param maxWidth Maximum width to decode this bitmap to, or zero for none
     * @param maxHeight Maximum height to decode this bitmap to, or zero for
     *            none
     * @param scaleType The ImageViews ScaleType used to calculate the needed image size.
     * @param decodeConfig Format to decode the bitmap to
     * @param errorListener Error listener, or null to ignore errors
     */
    public ImageRequest(String url, Response.Listener<Bitmap> listener, int maxWidth, int maxHeight,
            ScaleType scaleType, Config decodeConfig, Response.ErrorListener errorListener) {
        super(Method.GET, url, errorListener);
        setRetryPolicy(new DefaultRetryPolicy(DEFAULT_IMAGE_TIMEOUT_MS, DEFAULT_IMAGE_MAX_RETRIES,
                DEFAULT_IMAGE_BACKOFF_MULT));
        mListener = listener;
        mDecodeConfig = decodeConfig;
        mMaxWidth = maxWidth;
        mMaxHeight = maxHeight;
        mScaleType = scaleType;
    }

    /**
     * For API compatibility with the pre-ScaleType variant of the constructor. Equivalent to
     * the normal constructor with {@code ScaleType.CENTER_INSIDE}.
     *
     * 默认的图片缩放类型为 CENTER_INSIDES, 即居中，完整的显示出来
     */
    @Deprecated
    public ImageRequest(String url, Response.Listener<Bitmap> listener, int maxWidth, int maxHeight,
            Config decodeConfig, Response.ErrorListener errorListener) {
        this(url, listener, maxWidth, maxHeight,
                ScaleType.CENTER_INSIDE, decodeConfig, errorListener);
    }
    @Override
    public Priority getPriority() {
        return Priority.LOW;
    }

    /**
     * Scales one side of a rectangle to fit aspect ratio.
     *
     * 1. Primary   主边（返回主边的实际长度）
     * 2. Second    次边
     *
     * 1. Max       最大值
     * 2. Actual    实际值
     *
     * @param maxPrimary Maximum size of the primary dimension (i.e. width for
     *        max width), or zero to maintain aspect ratio with secondary
     *        dimension
     * @param maxSecondary Maximum size of the secondary dimension, or zero to
     *        maintain aspect ratio with primary dimension
     * @param actualPrimary Actual size of the primary dimension
     * @param actualSecondary Actual size of the secondary dimension
     * @param scaleType The ScaleType used to calculate the needed image size.
     */
    private static int getResizedDimension(int maxPrimary, int maxSecondary, int actualPrimary,
            int actualSecondary, ScaleType scaleType) {

        // If no dominant value at all, just return the actual.
        // 如果未设置最大值，则返回主边的实际长度
        if ((maxPrimary == 0) && (maxSecondary == 0)) {
            return actualPrimary;
        }

        // If ScaleType.FIT_XY fill the whole rectangle, ignore ratio.
        // FIX_XY 不按比例缩放，塞满整个View
        if (scaleType == ScaleType.FIT_XY) {
            if (maxPrimary == 0) {
                return actualPrimary;
            }
            return maxPrimary;
        }

        // If primary is unspecified, scale primary to match secondary's scaling ratio.
        // 如果主边为指定最大长度，则主边按照次边长度比例缩放
        if (maxPrimary == 0) {
            double ratio = (double) maxSecondary / (double) actualSecondary;
            return (int) (actualPrimary * ratio);
        }

        if (maxSecondary == 0) {
            return maxPrimary;
        }

        double ratio = (double) actualSecondary / (double) actualPrimary;
        int resized = maxPrimary;

        // If ScaleType.CENTER_CROP fill the whole rectangle, preserve aspect ratio.
        // 如果ScaleType为CENTER_CROP，即按比例缩放到图片的宽高，居中显示
        if (scaleType == ScaleType.CENTER_CROP) {
            if ((resized * ratio) < maxSecondary) {
                resized = (int) (maxSecondary / ratio);
            }
            return resized;
        }

        if ((resized * ratio) > maxSecondary) {
            resized = (int) (maxSecondary / ratio);
        }
        return resized;
    }

    @Override
    protected Response<Bitmap> parseNetworkResponse(NetworkResponse response) {
        // Serialize all decode on a global lock to reduce concurrent heap usage.
        // 所有的Decode 操作通过一个全局锁，减少并发时的Heap使用
        synchronized (sDecodeLock) {
            try {
                return doParse(response);
            } catch (OutOfMemoryError e) {
                VolleyLog.e("Caught OOM for %d byte image, url=%s", response.data.length, getUrl());
                return Response.error(new ParseError(e));
            }
        }
    }

    /**
     * The real guts of parseNetworkResponse. Broken out for readability.
     */
    private Response<Bitmap> doParse(NetworkResponse response) {
        byte[] data = response.data;
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        Bitmap bitmap = null;
        if (mMaxWidth == 0 && mMaxHeight == 0) {
            decodeOptions.inPreferredConfig = mDecodeConfig;
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
        } else {
            // If we have to resize this image, first get the natural bounds.
            decodeOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
            int actualWidth = decodeOptions.outWidth;
            int actualHeight = decodeOptions.outHeight;

            // Then compute the dimensions we would ideally like to decode to.
            int desiredWidth = getResizedDimension(mMaxWidth, mMaxHeight,
                    actualWidth, actualHeight, mScaleType);
            int desiredHeight = getResizedDimension(mMaxHeight, mMaxWidth,
                    actualHeight, actualWidth, mScaleType);

            // Decode to the nearest power of two scaling factor.
            decodeOptions.inJustDecodeBounds = false;
            // TODO(ficus): Do we need this or is it okay since API 8 doesn't support it?
            // decodeOptions.inPreferQualityOverSpeed = PREFER_QUALITY_OVER_SPEED;
            decodeOptions.inSampleSize =
                findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight);
            Bitmap tempBitmap =
                BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);

            // If necessary, scale down to the maximal acceptable size.
            if (tempBitmap != null && (tempBitmap.getWidth() > desiredWidth ||
                    tempBitmap.getHeight() > desiredHeight)) {
                bitmap = Bitmap.createScaledBitmap(tempBitmap,
                        desiredWidth, desiredHeight, true);
                tempBitmap.recycle();
            } else {
                bitmap = tempBitmap;
            }
        }

        if (bitmap == null) {
            return Response.error(new ParseError(response));
        } else {
            return Response.success(bitmap, HttpHeaderParser.parseCacheHeaders(response));
        }
    }

    @Override
    protected void deliverResponse(Bitmap response) {
        mListener.onResponse(response);
    }

    /**
     * Returns the largest power-of-two divisor for use in downscaling a bitmap
     * that will not result in the scaling past the desired dimensions.
     *
     * @param actualWidth Actual width of the bitmap
     * @param actualHeight Actual height of the bitmap
     * @param desiredWidth Desired width of the bitmap
     * @param desiredHeight Desired height of the bitmap
     */
    // Visible for testing.
    static int findBestSampleSize(
            int actualWidth, int actualHeight, int desiredWidth, int desiredHeight) {
        double wr = (double) actualWidth / desiredWidth;
        double hr = (double) actualHeight / desiredHeight;
        double ratio = Math.min(wr, hr);
        float n = 1.0f;
        while ((n * 2) <= ratio) {
            n *= 2;
        }

        return (int) n;
    }
}
