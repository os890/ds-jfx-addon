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
package org.os890.ds.jfx.impl;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.fxml.JavaFXBuilderFactory;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.apache.deltaspike.core.api.config.view.ViewConfig;
import org.apache.deltaspike.core.api.config.view.ViewRef;
import org.apache.deltaspike.core.api.message.LocaleResolver;
import org.apache.deltaspike.core.util.ExceptionUtils;
import org.os890.ds.jfx.api.ManagedNode;
import org.os890.ds.jfx.api.NewScene;
import org.os890.ds.jfx.api.Primary;
import org.os890.ds.jfx.api.SceneConfig;
import org.os890.ds.jfx.api.AsyncViewCreationEvent;
import org.os890.ds.jfx.api.ViewManager;

import javax.enterprise.context.Dependent;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.Specializes;
import javax.enterprise.inject.spi.InjectionPoint;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Specializes
public class JfxArtifactProducer extends ViewManager
{
    private static final String FXML = ".fxml";
    private static final String CSS = ".css";

    @Produces
    @Dependent
    public @Primary Stage exposePrimaryStage()
    {
        return primaryStage;
    }

    @Produces
    @Dependent
    public FXMLLoader produceInjectableLoader()
    {
        return new FXMLLoader(null, null, null, param -> resolveBean(param, false), StandardCharsets.UTF_8);
    }

    @Produces
    @ManagedNode(ViewRef.Manual.class /*just a placeholder*/)
    public Parent createNodeFor(InjectionPoint injectionPoint, LocaleResolver localeResolver)
    {
        ManagedNode managedNode = injectionPoint.getAnnotated().getAnnotation(ManagedNode.class);
        return createViewNode(localeResolver, managedNode.value());
    }

    @Produces
    @NewScene(ViewRef.Manual.class /*just a placeholder*/)
    public Scene createSceneFor(InjectionPoint injectionPoint, LocaleResolver localeResolver)
    {
        NewScene managedNode = injectionPoint.getAnnotated().getAnnotation(NewScene.class);
        Class<? extends ViewConfig> viewConfigClass = managedNode.value();
        SceneConfig sceneConfig = viewConfigClass.getAnnotation(SceneConfig.class);

        SceneConfig inlineSceneConfig = injectionPoint.getAnnotated().getAnnotation(SceneConfig.class);
        if (inlineSceneConfig != null)
        {
            sceneConfig = inlineSceneConfig;
        }

        Parent result = createViewNode(localeResolver, viewConfigClass, sceneConfig);

        if (sceneConfig != null)
        {
            return new Scene(result, sceneConfig.width(), sceneConfig.height());
        }
        return new Scene(result);
    }

    /*
     * view creation
     */
    public Parent createViewNode(LocaleResolver localeResolver, Class<? extends ViewConfig> viewConfigClass)
    {
        return createViewNode(localeResolver, viewConfigClass, null);
    }

    public Parent createViewNode(LocaleResolver localeResolver, Class<? extends ViewConfig> viewConfigClass, SceneConfig sceneConfig)
    {
        String simpleClassName = viewConfigClass.getSimpleName();
        String viewName = simpleClassName.substring(0, 1).toLowerCase() + simpleClassName.substring(1);

        if (sceneConfig == null)
        {
            sceneConfig = viewConfigClass.getAnnotation(SceneConfig.class);
        }

        URL fxmlInputStream = viewConfigClass.getResource(viewName + FXML);

        Locale locale = localeResolver.getLocale();

        String defaultBundleName = viewConfigClass.getPackage().getName() + "." + viewName;
        String bundleName = defaultBundleName;

        if (sceneConfig != null && !"".equals(sceneConfig.bundleName()))
        {
            bundleName = sceneConfig.bundleName();
        }

        ResourceBundle resourceBundle = null;
        try
        {
            resourceBundle = ResourceBundle.getBundle(bundleName, locale);
        }
        catch (MissingResourceException e)
        {
            if (!bundleName.equals(defaultBundleName))
            {
                throw e;
            }
        }

        try
        {
            Parent result = FXMLLoader.<Parent>load(fxmlInputStream, resourceBundle, new JavaFXBuilderFactory(), param -> resolveBean(param, false));

            String[] styleSheetNames;
            if (sceneConfig != null && sceneConfig.styleSheetNames().length > 1)
            {
                styleSheetNames = sceneConfig.styleSheetNames();
            }
            else
            {
                styleSheetNames = new String[]{viewName + CSS};
            }

            String[] styleSheetURLs = new String[styleSheetNames.length];

            for (int i = 0; i < styleSheetNames.length; i++)
            {
                URL stylesheetURL = viewConfigClass.getResource(styleSheetNames[i]);
                if (stylesheetURL != null)
                {
                    styleSheetURLs[i] = stylesheetURL.toExternalForm();
                }
            }
            result.getStylesheets().addAll(styleSheetURLs);
            result.getStylesheets().removeAll(Collections.<String>singleton(null));

            return result;
        }
        catch (IOException e)
        {
            throw ExceptionUtils.throwAsRuntimeException(e);
        }
    }

    /*
     * async
     */

    public void onAsyncViewCreationEvent(@Observes final AsyncViewCreationEvent asyncViewCreationEvent, final LocaleResolver localeResolver, @Primary Executor executor)
    {
        //wrap supplier once we need scope-handling
        Supplier<Parent> supplier = () -> createViewNode(localeResolver, asyncViewCreationEvent.getViewToCreate());
        CompletableFuture.supplyAsync(supplier, executor).thenAccept(createConsumerWrapper(asyncViewCreationEvent.getConsumer()) /*the add method*/);
    }

    //needed to use the ui-thread to update the ui with the result - javafx doesn't allow to do the update in a diff. thread
    private Consumer<Parent> createConsumerWrapper(final Consumer<Parent> originalConsumer)
    {
        return parent -> Platform.runLater(() -> {
            originalConsumer.accept(parent);
        });
    }

    //allows to provide a custom executor
    @Produces
    @Dependent
    protected @Primary Executor exposeExecutorForWorkers()
    {
        return Executors.newSingleThreadExecutor(); //don't use Platform::runLater it would block the ui-thread
    }
}
