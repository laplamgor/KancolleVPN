package xyz.hexene.localvpn;

import android.graphics.Bitmap;

import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by root on 15-12-10.
 */

public class LinkedHashMapTest {
    public static void main(String[] args) {
        Map map = new FixedSizeLinkedHashMap();
        System.out.println(map.size());
        for(int i = 0; i < 50; i++) {
            map.put(i, true);
            System.out.println(map.size());
            System.out.println(map);
        }
    }
}

class FixedSizeLinkedHashMap extends LinkedHashMap {
    private static final long serialVersionUID = 6918023506928428613L;
    private static int MAX_ENTRIES = 10;

    /**
     * 获得允许存放的最大容量
     * [url=home.php?mod=space&uid=7300]@return[/url] int
     */
    public static int getMAX_ENTRIES() {
        return MAX_ENTRIES;
    }

    /**
     * 设置允许存放的最大容量
     * @param int max_entries
     */
    public static void setMAX_ENTRIES(int max_entries) {
        MAX_ENTRIES = max_entries;
    }

    /**
     * 如果Map的尺寸大于设定的最大长度，返回true，再新加入对象时删除最老的对象
     * @param Map.Entry eldest
     * @return int
     */
    protected boolean removeEldestEntry(Map.Entry eldest) {
        return size() > MAX_ENTRIES;
    }
}
/*
private static final HashMap<String, Bitmap> sHardBitmapCache = new LinkedHashMap<String, Bitmap>(
        HARD_CACHE_CAPACITY / 2, 0.75f, true)
{
    private static final long serialVersionUID = -57738079457331894L;

    @Override
    protected boolean removeEldestEntry(
            LinkedHashMap.Entry<String, Bitmap> eldest)
    {
        if (size() > HARD_CACHE_CAPACITY)
        {
            // 当然缓存图片数超过强引用缓存最大容量时，把最后一张图片移入弱引用缓存
            sSoftBitmapCache.put(eldest.getKey(),
                    new SoftReference<Bitmap>(eldest.getValue()));
            return true;
        }
        else
            return false;
    }
};
*/