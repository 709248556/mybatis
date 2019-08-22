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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.*;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/**
 * {@link ResultSet} 包装器，可以理解成 ResultSet 的工具类，提供给 {@link DefaultResultSetHandler} 使用
 *
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

    /**
     * ResultSet 对象
     */
    private final ResultSet resultSet;
    private final TypeHandlerRegistry typeHandlerRegistry;
    /**
     * 记录了ResultSet中每列的列名
     */
    private final List<String> columnNames = new ArrayList<>();
    /**
     * 记录ResultSet中每列对应的Java类型
     */
    private final List<String> classNames = new ArrayList<>();
    /**
     * 记录ResultSet中每列对应的JdbcType类型
     */
    private final List<JdbcType> jdbcTypes = new ArrayList<>();
    /**
     * 记录了每列对应的TypeHandler对象，key是列名，value是TypeHandler集合
     *
     * KEY1：字段的名字
     * KEY2：Java 属性类型
     */
    private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
    /**
     * 记录了被映射的列名，其中key是ResultMap对象的时，value是该ResultMap对象映射的列名集合
     *
     * KEY：{@link #getMapKey(ResultMap, String)}
     * VALUE：字段的名字的数组
     */
    private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
    /**
     * 记录了未映射的列名，其中key是ResultMap对象的时，value是该ResultMap对象未映射的71］名集合
     *
     * 和 {@link #mappedColumnNamesMap} 相反
     */
    private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

    public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.resultSet = rs;
        // 遍历 ResultSetMetaData 的字段们，解析出 columnNames、jdbcTypes、classNames 属性
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columnCount = metaData.getColumnCount();//ResultSet中的列数
        for (int i = 1; i <= columnCount; i++) {
            //获取列名或是通过”AS”关键字指定的别名
            columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
            jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));//该列的JdbcType类型
            classNames.add(metaData.getColumnClassName(i));//该列对应的Java类型
        }
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    public List<String> getColumnNames() {
        return this.columnNames;
    }

    public List<String> getClassNames() {
        return Collections.unmodifiableList(classNames);
    }

    public List<JdbcType> getJdbcTypes() {
        return jdbcTypes;
    }

    public JdbcType getJdbcType(String columnName) {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnName)) {
                return jdbcTypes.get(i);
            }
        }
        return null;
    }

    /**
     * Gets the type handler to use when reading the result set.
     * Tries to get from the TypeHandlerRegistry by searching for the property type.
     * If not found it gets the column JDBC type and tries to get a handler for it.
     *
     * 获得指定字段名的指定 JavaType 类型的 TypeHandler 对象
     *
     * @param propertyType JavaType
     * @param columnName 执行字段
     * @return TypeHandler 对象
     */
    @SuppressWarnings("Duplicates")
    public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
        TypeHandler<?> handler = null;
        // 先从缓存的 typeHandlerMap 中，获得指定字段名的指定 JavaType 类型的 TypeHandler 对象
        Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
        if (columnHandlers == null) {
            columnHandlers = new HashMap<>();
            typeHandlerMap.put(columnName, columnHandlers);
        } else {
            handler = columnHandlers.get(propertyType);
        }
        // 如果获取不到，则进行查找
        if (handler == null) {
            // 获得 JdbcType 类型
            JdbcType jdbcType = getJdbcType(columnName);
            // 获得 TypeHandler 对象
            handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
            // Replicate logic of UnknownTypeHandler#resolveTypeHandler
            // See issue #59 comment 10
            // 如果获取不到，则再次进行查找
            if (handler == null || handler instanceof UnknownTypeHandler) {
                // 使用 classNames 中的类型，进行继续查找 TypeHandler 对象
                final int index = columnNames.indexOf(columnName);
                final Class<?> javaType = resolveClass(classNames.get(index));
                if (javaType != null && jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
                } else if (javaType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType);
                } else if (jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(jdbcType);
                }
            }
            // 如果获取不到，则使用 ObjectTypeHandler 对象
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = new ObjectTypeHandler();
            }
            // 缓存到 typeHandlerMap 中
            columnHandlers.put(propertyType, handler);
        }
        return handler;
    }

    private Class<?> resolveClass(String className) {
        try {
            // #699 className could be null
            if (className != null) {
                return Resources.classForName(className);
            }
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return null;
    }

    private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        //mappedColumnNames和unmappedColumnNames分别记录ResultMap中映射的列名和未映射的列名
        List<String> mappedColumnNames = new ArrayList<>();
        List<String> unmappedColumnNames = new ArrayList<>();
        // 将 columnPrefix 转换成大写，并拼接到 resultMap.mappedColumns 属性上
        final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
        final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
        // 遍历 columnNames 数组，根据是否在 mappedColumns 中，分别添加到 mappedColumnNames 和 unmappedColumnNames 中
        for (String columnName : columnNames) {
            final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
            if (mappedColumns.contains(upperColumnName)) {
                mappedColumnNames.add(upperColumnName);
            } else {
                unmappedColumnNames.add(columnName);
            }
        }
        // 将 mappedColumnNames 和 unmappedColumnNames 结果，添加到 mappedColumnNamesMap 和 unMappedColumnNamesMap 中
        mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
        unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
    }

    //该方法返回指定ResultMap对象中明确映射的列名集合，同时会将该列名集合以及未映射的列名集合记录到mappedColumnNamesMap 和unMappedColumnNamesMap中缓存
    public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        // 在mappedColumnNamesMap集合中查找被映射的列名，其中key是由ResultMap的id与列前级组成
        List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        if (mappedColumnNames == null) {
            // 未查找到指定ResultMap映射的列名，则加载后存入到mappedColumnNamesMap集合中
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            // 重新获得对应的 mapped 数组
            mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return mappedColumnNames;
    }

    public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        // 获得对应的 unMapped 数组
        List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        if (unMappedColumnNames == null) {
            // 初始化
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            // 重新获得对应的 unMapped 数组
            unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return unMappedColumnNames;
    }

    private String getMapKey(ResultMap resultMap, String columnPrefix) {
        return resultMap.getId() + ":" + columnPrefix;
    }

    private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
        // 直接返回 columnNames ，如果符合如下任一情况
        if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
            return columnNames;
        }
        // 拼接前缀 prefix ，然后返回
        final Set<String> prefixed = new HashSet<>();
        for (String columnName : columnNames) {
            prefixed.add(prefix + columnName);
        }
        return prefixed;
    }

}
