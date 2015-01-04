/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.os890.ds.jfx.api;

import javafx.stage.Stage;
import org.apache.deltaspike.cdise.api.CdiContainer;
import org.apache.deltaspike.cdise.api.CdiContainerLoader;
import org.apache.deltaspike.core.api.provider.BeanProvider;
import org.apache.deltaspike.core.util.ClassUtils;
import org.apache.deltaspike.core.util.ExceptionUtils;
import org.apache.deltaspike.core.util.ProxyUtils;
import org.apache.deltaspike.core.util.ReflectionUtils;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import java.lang.reflect.Method;

@ApplicationScoped
public class ViewManager
{
    private static CdiContainer container = CdiContainerLoader.getCdiContainer();

    protected static Stage primaryStage;

    public static void start(Stage primaryStage)
    {
        start(null, primaryStage);
    }

    public static <T> T start(Class<T> injectionAwareEntryPointClass, Stage primaryStage)
    {
        if (primaryStage == null)
        {
            throw new IllegalArgumentException("no stage provided");
        }

        ViewManager.primaryStage = primaryStage;

        container.boot();
        container.getContextControl().startContexts();

        if (injectionAwareEntryPointClass != null)
        {
            return initSceneStarter(injectionAwareEntryPointClass);
        }
        return null;
    }

    public static <T> T initSceneStarter(Class<T> injectionAwareEntryPointClass)
    {
        T entryPoint = ClassUtils.tryToInstantiateClass(injectionAwareEntryPointClass);
        BeanProvider.injectFields(entryPoint);

        for (Method method : injectionAwareEntryPointClass.getDeclaredMethods())
        {
            if (method.getReturnType().equals(Void.TYPE) &&
                method.getParameterCount() == 0 &&
                method.getAnnotation(PostConstruct.class) != null)
            {
                try
                {
                    ReflectionUtils.invokeMethod(entryPoint, method, injectionAwareEntryPointClass, true);
                }
                catch (IllegalAccessException e)
                {
                    throw ExceptionUtils.throwAsRuntimeException(e);
                }
                break;
            }
        }
        return entryPoint;
    }

    public static void stop()
    {
        container.shutdown();
    }

    public <T> T resolveBean(Class<T> beanClass, boolean allowProxiedBeans)
    {
        T result = BeanProvider.getContextualReference(beanClass, true);

        if (result == null)
        {
            return ClassUtils.tryToInstantiateClass(beanClass);
        }
        else if (!allowProxiedBeans && ProxyUtils.isProxiedClass(result.getClass()))
        {
            return ClassUtils.tryToInstantiateClass(beanClass);
        }
        return result;
    }
}
