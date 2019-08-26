/**
 * Copyright 2009-2018 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * Executor 基类，提供骨架方法，从而使子类只要实现指定的几个抽象方法即可
 *
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    /**
     * 事务对象，实现事务的提交、回j哀和关闭操作
     */
    protected Transaction transaction;
    /**
     * 包装的 Executor 对象
     */
    protected Executor wrapper;

    /**
     * DeferredLoad( 延迟加载 ) 队列
     */
    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    /**
     * 本地缓存，即一级缓存
     *
     * 在执行查询操作时，会先查询一级缓存，如果其中存在完全一样的查询语旬，则直接从一级缓存中取出相应的结果对象并返回给用户
     */
    protected PerpetualCache localCache;
    /**
     * 本地缓存，输出类型的参数
     */
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    /**
     * 记录嵌套查询的层级
     */
    protected int queryStack;
    /**
     * 是否关闭
     */
    private boolean closed;

    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        this.wrapper = this; // 自己
    }

    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            // 回滚事务
            try {
                rollback(forceRollback);
            } finally {
                // 关闭事务
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore.  There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            // 置空变量
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        // 已经关闭，则抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 清空本地缓存,clearLocalCache（）方法中会调用localCache、localOutputParameterCache两个缓存的clear（）方法完成清理工作。
        // 这是影响一级缓存中数据存活时长的第三个方面
        clearLocalCache();
        // 执行写操作
        return doUpdate(ms, parameter);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        // 已经关闭，则抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 执行刷入批处理语句
        return doFlushStatements(isRollBack);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        // 获得 BoundSql 对象
        BoundSql boundSql = ms.getBoundSql(parameter);
        // 创建 CacheKey 对象
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        // 查询
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        // 已经关闭，则抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 清空本地缓存，如果 queryStack 为零，并且要求清空本地缓存。
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            clearLocalCache();
        }
        List<E> list;
        try {
            // queryStack + 1
            queryStack++;//增加查询层数
            // 从一级缓存中，获取查询结果
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            // 获取到，则进行处理
            if (list != null) {
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            // 获得不到，则从数据库中查询
            } else {
              //其中会调用doQuery（）方法完成数据库查询，并得到映射后的结采对象，doQuery（）方法是一个抽象方法
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            // queryStack - 1
            queryStack--;
        }
        //在最外层的查询结束时，所有嵌套查询也已经完成，相关缓存项也已经完全加载，所以在这里可以
        //触发DeferredLoad加载一级缓存中记录的嵌套查询的结果对象
        if (queryStack == 0) {
            // 执行延迟加载
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            //加裁完成后，清空deferredLoads集合
            deferredLoads.clear();
            // 如果缓存级别是 LocalCacheScope.STATEMENT ，则进行清理
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                // issue #482
                //根据localCacheScope配置决定是否清空一级缓存，localCacheScope配置是影响一级缓存中结果对象存活时长的第二个方面
                clearLocalCache();
            }
        }
        return list;
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        // 获得 BoundSql 对象
        BoundSql boundSql = ms.getBoundSql(parameter);
        // 执行查询
        return doQueryCursor(ms, parameter, rowBounds, boundSql);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        // 如果执行器已关闭，抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 创建 DeferredLoad 对象
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        // 如果可加载，则执行加载
        if (deferredLoad.canLoad()) {
          //一级缓存中已经记录了指定查询的结果对象，直接从缓存中加载对象，并设直到外层对象中
            deferredLoad.load();
        // 如果不可加载，则添加到 deferredLoads 中
        } else {
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 创建 CacheKey 对象
        CacheKey cacheKey = new CacheKey();
        // 设置 id、offset、limit、sql 到 CacheKey 对象中
        cacheKey.update(ms.getId());
        cacheKey.update(rowBounds.getOffset());
        cacheKey.update(rowBounds.getLimit());
        cacheKey.update(boundSql.getSql());
        // 设置 ParameterMapping 数组的元素对应的每个 value 到 CacheKey 对象中
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        // mimic DefaultParameterHandler logic 这块逻辑，和 DefaultParameterHandler 获取 value 是一致的。
        //获取用户传入的实参，并添加.f1JCacheKey对象中
        for (ParameterMapping parameterMapping : parameterMappings) {
            if (parameterMapping.getMode() != ParameterMode.OUT) { //过滤掉输出类型的参数
                Object value;
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                cacheKey.update(value);//将实参添加到CacheKey对象中
            }
        }
        // 设置 Environment.id 到 CacheKey 对象中
        if (configuration.getEnvironment() != null) {
            // issue #176
            cacheKey.update(configuration.getEnvironment().getId());
        }
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return localCache.getObject(key) != null;
    }

    @Override
    public void commit(boolean required) throws SQLException {
        // 已经关闭，则抛出 ExecutorException 异常
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        // 清空本地缓存
        clearLocalCache();
        // 刷入批处理语句
        flushStatements();//执行缓存的SQL语句，其中调用了flushStatements(false）方法
        // 是否要求提交事务。如果是，则提交事务。
        if (required) {
            transaction.commit();
        }
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                // 清空本地缓存
                clearLocalCache();
                // 刷入批处理语句
                flushStatements(true);
            } finally {
                if (required) {
                    // 是否要求回滚事务。如果是，则回滚事务。
                    transaction.rollback();
                }
            }
        }
    }

    @Override
    public void clearLocalCache() {
        if (!closed) {
            // 清理 localCache
            localCache.clear();
            // 清理 localOutputParameterCache
            localOutputParameterCache.clear();
        }
    }

    protected abstract int doUpdate(MappedStatement ms, Object parameter)
            throws SQLException;

    protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
            throws SQLException;

    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException;

    protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
            throws SQLException;

    // 关闭 Statement 对象
    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                if (!statement.isClosed()) {
                    statement.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * 设置事务超时时间
     *
     * Apply a transaction timeout.
     * @param statement a current statement
     * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
     * @since 3.4.0
     * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
     */
    protected void applyTransactionTimeout(Statement statement) throws SQLException {
        StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
    }

    // 存储过程相关，在一级缓存命中时，获取缓存中保存的输出类型参数，并设置到用户传入的实参（parameter）对象中
    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    // 从数据库中读取操作
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        // 在缓存中，添加占位对象。此处的占位符，和延迟加载有关，可见 `DeferredLoad#canLoad()` 方法
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            // 执行读操作
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            // 从缓存中，移除占位对象
            localCache.removeObject(key);
        }
        // 添加到缓存中
        localCache.putObject(key, list);
        if (ms.getStatementType() == StatementType.CALLABLE) {//是否为存储过程调用
            localOutputParameterCache.putObject(key, parameter);//缓存输出类型的参数
        }
        return list;
    }

    // 获得 Connection 对象
    protected Connection getConnection(Log statementLog) throws SQLException {
        // 获得 Connection 对象
        Connection connection = transaction.getConnection();
        // 如果 debug 日志级别，则创建 ConnectionLogger 对象，进行动态代理
        if (statementLog.isDebugEnabled()) {
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    private static class DeferredLoad {

        private final MetaObject resultObject;//外层对象对应的MetaObject对象
        private final String property;//延迟加载的属性名称
        private final Class<?> targetType;//延迟加载的属性的类型
        private final CacheKey key;//延迟加载的结果对象在一级缓存中相应的CacheKey对象
        private final PerpetualCache localCache;//一级缓存，与BaseExecutor.localCache字段指向同－PerpetualCache对象
        private final ObjectFactory objectFactory;
        private final ResultExtractor resultExtractor;//ResultExtractor负责结果对象的类型转换，

        // issue #781
        public DeferredLoad(MetaObject resultObject,
                            String property,
                            CacheKey key,
                            PerpetualCache localCache,
                            Configuration configuration,
                            Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        /*
        说明“完全加载”的含义：BaseExecutor.叩ieryFromDatabase（）方法中，
        开始查询调用doQuery（）方法查询数据库之前，会先在localCache中添加占位符，待查询完成之后，
        才将真正的结果对象放到local Cache中缓存，此时该缓存项才算“完全加载”
         */
        //负责检测缓存项是否已经完全加载到了缓存中
        public boolean canLoad() {
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        //负责从缓存中加载结果对象，并设置到外层对象的相应属性中
        public void load() {
            @SuppressWarnings("unchecked")
            // we suppose we get back a List
            // 从缓存 localCache 中获取指定的结采对象
            List<Object> list = (List<Object>) localCache.getObject(key);
            // 解析结果转换成指定类型
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            // 设置到 resultObject 的对应属性
            resultObject.setValue(property, value);
        }

    }

}
