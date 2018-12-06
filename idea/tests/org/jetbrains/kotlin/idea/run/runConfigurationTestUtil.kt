/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.run

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManagerEx
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.configurations.JavaCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.psi.PsiElement
import com.intellij.testFramework.MapDataContext
import org.junit.Assert

fun getJavaRunParameters(configuration: RunConfiguration): JavaParameters {
    val state = configuration.getState(MockExecutor, ExecutionEnvironmentBuilder.create(configuration.project, MockExecutor, MockProfile).build())

    Assert.assertNotNull(state)
    Assert.assertTrue(state is JavaCommandLine)

    configuration.checkConfiguration()
    return (state as JavaCommandLine).javaParameters!!
}

fun findContextConfigurations(element: PsiElement): List<ConfigurationFromContext> {
    val dataContext = MapDataContext()
    val location = PsiLocation(element.project, element)
    dataContext.put(Location.DATA_KEY, location)
    dataContext.put(LangDataKeys.MODULE, location.module)

    return ConfigurationContext.getFromContext(dataContext).configurationsFromContext.orEmpty()
}

fun createConfigurationFromElement(element: PsiElement, save: Boolean = false): RunConfiguration {
    val runnerAndConfigurationSettings = findContextConfigurations(element).single().configurationSettings
    if (save) {
        RunManagerEx.getInstanceEx(element.project).setTemporaryConfiguration(runnerAndConfigurationSettings)
    }
    return runnerAndConfigurationSettings.configuration
}


private object MockExecutor : DefaultRunExecutor() {
    override fun getId() = DefaultRunExecutor.EXECUTOR_ID
}

private object MockProfile : RunProfile {
    override fun getState(executor: Executor, env: ExecutionEnvironment) = null
    override fun getIcon() = null
    override fun getName() = "unknown"
}
