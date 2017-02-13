package com.example.root.analyzediskcache;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.example.root.analyzediskcache.blobcache.BlobCache;
import com.example.root.analyzediskcache.blobcache.CacheManager;
import com.example.root.analyzediskcache.blobcache.Utils;
import com.example.root.analyzediskcache.disklrucache.DiskLruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final String RESULT_STATISTICS_HOLDER = "writing: blobCache = %d, DiskLruCache = %d\nreading: blobCache = %d, DiskLruCache = %d";

    private static final int IMAGE_CACHE_MAX_ENTRIES = 5000;
    private static final int IMAGE_CACHE_MAX_BYTES = 200 * 1024 * 1024;
    private static final int IMAGE_CACHE_VERSION = 1;
    private final static int BUFFER_SIZE = 4096;

    private static final String[] STORE_IMAGES_PROJECTIONS = {
            MediaStore.Images.Media.DATA
    };

    private static final String IMAGE_CACHE_FILE_BLOBCHCHE = "image_blobcache";
    private static final String IMAGE_CACHE_FILE_DISKLRUCHCHE = "image_disklrucache";

    private static final int BYTESBUFFE_POOL_SIZE = 4;
    private static final int BYTESBUFFER_SIZE = 200 * 1024;

    private static final BytesBufferPool bufferPool =
            new BytesBufferPool(BYTESBUFFE_POOL_SIZE, BYTESBUFFER_SIZE);

    private BlobCache mBlobCache;
    private DiskLruCache mDiskLruCache;

    private TextView resultTv;
    private TextView statisticsTv;
    private Button loadImageBtn;
    private Button writingCompareBtn;
    private Button readingCompareBtn;

    private List<String> imagePaths = new ArrayList<>();
    private int imageIndex = 0;

    // x for blobCache, y for diskLruCache
    private List<Point> writingTime = new ArrayList<>();
    private List<Point> readingTime = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        resultTv = (TextView) findViewById(R.id.tv_result);
        statisticsTv = (TextView) findViewById(R.id.tv_statistics);
        loadImageBtn = (Button) findViewById(R.id.btn_load_image);
        writingCompareBtn = (Button) findViewById(R.id.btn_compare_writing);
        readingCompareBtn = (Button) findViewById(R.id.btn_compare_reading);
        try {
            initCache();
        } catch (IOException e) {
            e.printStackTrace();
        }
        initViews();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBlobCache.close();
        try {
            mDiskLruCache.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initCache() throws IOException {
        mBlobCache = CacheManager.getCache(this, IMAGE_CACHE_FILE_BLOBCHCHE,
                IMAGE_CACHE_MAX_ENTRIES, IMAGE_CACHE_MAX_BYTES,
                IMAGE_CACHE_VERSION);

        File cacheDir = getExternalCacheDir();
        String cachePath = cacheDir.getAbsolutePath() + "/" + IMAGE_CACHE_FILE_DISKLRUCHCHE;
        mDiskLruCache = DiskLruCache.open(new File(cachePath), IMAGE_CACHE_VERSION, 1, IMAGE_CACHE_MAX_BYTES);
    }

    private void initViews() {
        loadImageBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentResolver resolver = getContentResolver();
                Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, STORE_IMAGES_PROJECTIONS, null, null, null);
                if (cursor != null) {
                    while(cursor.moveToNext()) {
                        String path = cursor.getString(0);
                        if (path != null && !path.equals("") && path.endsWith("jpg")) {
                            imagePaths.add(path);
                        }
                    }
                    log("load image: size = " + imagePaths.size());
                    cursor.close();
                } else {
                    Toast.makeText(MainActivity.this, "Load Image Fail!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        writingCompareBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String imagePath = getNextImage();
                logImageInfo(imagePath);

                long startTime = System.currentTimeMillis();
                int costTimeBlobCache = 0;
                int costTimeDiskLruCache = 0;
                boolean valid = true;
                if (cacheImageByBlobCache(imagePath)) {
                    costTimeBlobCache = (int) (System.currentTimeMillis() - startTime);
                    log("cache " + imagePath + " by [blobCache] in " + costTimeBlobCache);
                } else {
                    log("cache " + imagePath + " by [blobCache] fail!");
                    valid = false;
                }

                startTime = System.currentTimeMillis();
                if (cacheImageByDiskLruCache(imagePath)) {
                    costTimeDiskLruCache = (int) (System.currentTimeMillis() - startTime);
                    log("cache " + imagePath + " by [diskLruCache] in " + costTimeDiskLruCache);
                } else {
                    log("cache " + imagePath + " by [diskLruCache] fail!");
                    valid = false;
                }

                if (valid) {
                    writingTime.add(new Point(costTimeBlobCache, costTimeDiskLruCache));
                    calculateStatistic();
                }
            }
        });

        readingCompareBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String imagePath = getCurrentImage();
                logImageInfo(imagePath);

                BytesBufferPool.BytesBuffer bytesBuffer = bufferPool.get();
                long startTime = System.currentTimeMillis();
                int costTimeBlobCache = 0;
                int costTimeDiskLruCache = 0;
                boolean valid = true;
                if (getImageByBlobCache(imagePath, bytesBuffer)) {
                    costTimeBlobCache = (int) (System.currentTimeMillis() - startTime);
                    log("load cache " + imagePath + " by [blobCache] in " + costTimeBlobCache);
                } else {
                    log("load cache " + imagePath + " by [blobCache] fail!");
                    valid = false;
                }

                startTime = System.currentTimeMillis();
                if (getImageByDiskLruCache(imagePath, bytesBuffer)) {
                    costTimeDiskLruCache = (int) (System.currentTimeMillis() - startTime);
                    log("load cache " + imagePath + " by [diskLruCache] in " + costTimeDiskLruCache);
                } else {
                    log("load cache " + imagePath + " by [diskLruCache] fail!");
                    valid = false;
                }

                if (valid) {
                    readingTime.add(new Point(costTimeBlobCache, costTimeDiskLruCache));
                    calculateStatistic();
                }
            }
        });
    }

    private void calculateStatistic() {
        int wBlobCache = 0;
        int wDiskLruCache = 0;
        int rBlobCache = 0;
        int rDiskLruCache = 0;
        for (Point point : writingTime) {
            wBlobCache += point.x;
            wDiskLruCache += point.y;
        }
        if (writingTime.size() > 0) {
            wBlobCache /= writingTime.size();
            wDiskLruCache /= writingTime.size();
        }

        for (Point point : readingTime) {
            rBlobCache += point.x;
            rDiskLruCache += point.y;
        }
        if (readingTime.size() > 0) {
            rBlobCache /= readingTime.size();
            rDiskLruCache /= readingTime.size();
        }

        statisticsTv.setText(String.format(Locale.CHINA, RESULT_STATISTICS_HOLDER, wBlobCache, wDiskLruCache, rBlobCache, rDiskLruCache));
    }

    private void log(String content) {
        Log.d(TAG, content);
        resultTv.append(content);
        resultTv.append("\n");
    }

    private void logImageInfo(String imagePath) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(imagePath, options);
        log(imagePath + " width = " + options.outWidth + " height = " + options.outHeight);
    }

    private String getCurrentImage() {
        return imagePaths.get(imageIndex);
    }

    private String getNextImage() {
        imageIndex++;
        if (imageIndex >= imagePaths.size()) {
            imageIndex = 0;
        }

        return imagePaths.get(imageIndex);
    }

    private boolean cacheImageByBlobCache(String path) {
        byte[] key = Utils.getBytes(path);
        long cacheKey = Utils.crc64Long(key);
        byte[] value = bitmapToBytes(decodeBitmap(path));
        ByteBuffer buffer = ByteBuffer.allocate(key.length + value.length);
        buffer.put(key);
        buffer.put(value);
        try {
            mBlobCache.insert(cacheKey, buffer.array());
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        mBlobCache.syncAll();
        return true;
    }

    private boolean cacheImageByDiskLruCache(String path) {
        String key = Utils.hashKeyForDisk(path);
        try {
            DiskLruCache.Editor editor = mDiskLruCache.edit(key);
            if (editor != null) {
                OutputStream outputStream = editor.newOutputStream(0);
                if (writeToStream(path, outputStream)) {
                    editor.commit();
                } else {
                    editor.abort();
                }
            }
            mDiskLruCache.flush();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean writeToStream(String path, OutputStream outputStream) {
        byte[] values = bitmapToBytes(decodeBitmap(path));
        try {
            outputStream.write(values);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean getImageByBlobCache(String path, BytesBufferPool.BytesBuffer buffer) {
        byte[] key = Utils.getBytes(path);
        long cacheKey = Utils.crc64Long(key);
        try {
            BlobCache.LookupRequest request = new BlobCache.LookupRequest();
            request.key = cacheKey;
            request.buffer = buffer.data;
            if (!mBlobCache.lookup(request)) return false;
            if (isSameKey(key, request.buffer)) {
                buffer.data = request.buffer;
                buffer.offset = key.length;
                buffer.length = request.length - buffer.offset;
                return true;
            }
        } catch (IOException ex) {
            // ignore.
        }
        return false;
    }

    private boolean getImageByDiskLruCache(String path, BytesBufferPool.BytesBuffer buffer) {
        String key = Utils.hashKeyForDisk(path);
        try {
            DiskLruCache.Snapshot snapShot = mDiskLruCache.get(key);
            if (snapShot != null) {
                InputStream is = snapShot.getInputStream(0);
                byte[] data = inputStreamToBytes(is);
                buffer.data = data;
                buffer.offset = 0;
                buffer.length = data.length;
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isSameKey(byte[] key, byte[] buffer) {
        int n = key.length;
        if (buffer.length < n) {
            return false;
        }
        for (int i = 0; i < n; ++i) {
            if (key[i] != buffer[i]) {
                return false;
            }
        }
        return true;
    }

    private Bitmap decodeBitmap(String path) {
        return BitmapFactory.decodeFile(path);
    }

    private byte[] bitmapToBytes(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        return baos.toByteArray();
    }

    private byte[] inputStreamToBytes(InputStream in) throws IOException{
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        byte[] data = new byte[BUFFER_SIZE];
        int count = -1;
        while((count = in.read(data, 0, BUFFER_SIZE)) != -1)
            outStream.write(data, 0, count);
        data = null;
        return outStream.toByteArray();
    }
}
