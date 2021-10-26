/*
 * Copyright(C) 2013 Agree Corporation. All rights reserved.
 *
 * Contributors:
 *     Agree Corporation - initial API and implementation
 */
package cn.com.agree.eureka.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可命名的线程工厂
 *
 * @author linzhi
 * @date Aug 22, 2013 3:56:05 PM
 * @version 1.0
 *
 */
public class NamedThreadFactory implements ThreadFactory {
    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger(1);
    private final ThreadGroup GROUP;
    private final boolean DAEMON;
    private String namePrefix = "EUREKA-Thread";

    private Map<String, ThreadMeta> tmeta = new HashMap<>();

    public NamedThreadFactory() {
        this(null, false);
    }

    public NamedThreadFactory(String namePrefix) {
        this(namePrefix, false);
    }

    public NamedThreadFactory(String namePrefix, boolean daemon) {
        if (namePrefix != null) {
            this.namePrefix = namePrefix;
        }
        this.DAEMON = daemon;
        SecurityManager s = System.getSecurityManager();
        GROUP = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
    }

    /*
     * @see java.util.concurrent.ThreadFactory#newThread(java.lang.Runnable)
     */
    @Override
    public Thread newThread(Runnable r) {
        String name = namePrefix + "-" + THREAD_NUMBER.getAndIncrement();
        Thread t = new Thread(GROUP, r, name, 0);

        if (t.isDaemon() != DAEMON) {
            t.setDaemon(DAEMON);
        }
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }

        ThreadMeta meta = new ThreadMeta(t.getId(), name, System.currentTimeMillis());
        this.tmeta.put(name, meta);
        return t;
    }

    public Map<String, ThreadMeta> getTmeta() {
        return tmeta;
    }

    public static class ThreadMeta{
        long id;
        String name;
        long createTime;
        long lastExecute;

        public ThreadMeta(long id, String name, long createTime) {
            this.id = id;
            this.name = name;
            this.createTime = createTime;
        }

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(long createTime) {
            this.createTime = createTime;
        }

        public long getLastExecute() {
            return lastExecute;
        }

        public void setLastExecute(long lastExecute) {
            this.lastExecute = lastExecute;
        }
    }

}
