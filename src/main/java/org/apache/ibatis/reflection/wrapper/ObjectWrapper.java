/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * 对象包装器接口，基于 {@link org.apache.ibatis.reflection.MetaClass} 工具类，定义对指定对象的各种操作。
 *
 * @author Clinton Begin
 */
public interface ObjectWrapper {

    /**
     * 获得值
     *
     * 如采ObjectWrapper中封装的是普通的Bean对象，则调用相应属性的相应getter方法，
     * 如采封装的是集合类，则获取指定key或下标对应的value值
     *
     * @param prop PropertyTokenizer 对象，相当于键
     * @return 值
     */
    Object get(PropertyTokenizer prop);

    /**
     * 设置值
     *
     * 如果ObjectWrapper中封装的是普通的Bean对象，则调用相应属性的相应setter方法，
     * 如果封装的是集合类，则设置指定key或下标对应的value值
     *
     * @param prop PropertyTokenizer 对象，相当于键
     * @param value 值
     */
    void set(PropertyTokenizer prop, Object value);

    /**
     * 查找属性表达式指定的属性，第二个参数表示是否忽略属性表达式中的下画线
     *
     * {@link MetaClass#findProperty(String, boolean)}
     */
    String findProperty(String name, boolean useCamelCaseMapping);

    /**
     * 查找可写属性的名称集合
     *
     * {@link MetaClass#getGetterNames()}
     */
    String[] getGetterNames();//

    /**
     * 查找可读属性的名称集合
     *
     * {@link MetaClass#getSetterNames()}
     */
    String[] getSetterNames();

    /**
     * 解析属性表达式指定属性的setter方法的参数类型
     *
     * {@link MetaClass#getSetterType(String)}
     */
    Class<?> getSetterType(String name);

    /**
     * 解析属性表达式指定属性的getter方法的返回值类型
     *
     * {@link MetaClass#getGetterType(String)}
     */
    Class<?> getGetterType(String name);

    /**
     * 判断属性表达式指定属性是否有getter/setter方法
     *
     * {@link MetaClass#hasSetter(String)}
     */
    boolean hasSetter(String name);

    /**
     * 判断属性表达式指定属性是否有getter/setter方法
     *
     * {@link MetaClass#hasGetter(String)}
     */
    boolean hasGetter(String name);

    /**
     * 为属性表达式指定的属性创建相应的MetaObject对象
     *
     * {@link MetaObject#forObject(Object, ObjectFactory, ObjectWrapperFactory, ReflectorFactory)}
     */
    MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

    /**
     * 是否为集合,也就是Collection类型
     */
    boolean isCollection();

    /**
     * 添加元素到集合
     */
    void add(Object element);

    /**
     * 添加多个元素到集合
     */
    <E> void addAll(List<E> element);

}