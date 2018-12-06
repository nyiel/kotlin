/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.run

import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.ConfigurationFromContextImpl
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.kotlin.idea.stubs.AbstractMultiModuleTest
import org.jetbrains.kotlin.idea.test.PluginTestCaseBase
import org.jetbrains.kotlin.idea.test.allKotlinFiles
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.idea.util.projectStructure.getModuleDir
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.kotlin.utils.sure
import org.junit.Assert
import java.io.File
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

abstract class AbstractContextRunConfigurationTest : AbstractMultiModuleTest() {
    fun doTest(path: String) {
        setUpModulePaths(path)

        checkFiles({ project.allKotlinFiles() }) {
            checkHighlighting(editor, false, false)

            val element = file.findElementAt(editor.caretModel.offset)!!
            val runConfiguration = findContextConfigurations(element).single()

            val expectedMap = extractExpectedDataToMap()
            val actualMap = runConfiguration.extractDataToMap()
            actualMap.assertContainsAll(expectedMap)
        }
    }

    private fun Map<String, String>.assertContainsAll(
        expectedMap: Map<String, String>
    ) {
        for ((name, expectedValue) in expectedMap) {
            val actualValue = this[name]
            if (actualValue == null) {
                Assert.fail("Run configuration doesn't have a property $name")
            }
            Assert.assertEquals("$name should be $expectedValue, but was $actualValue", expectedValue, actualValue)
        }
    }

    private fun extractExpectedDataToMap(): Map<String, String> {
        val comment = file.findDescendantOfType<PsiComment> {
            it.tokenType == KtTokens.BLOCK_COMMENT
        }.sure { "No comment found" }
        return comment.text.lines().map { it.trim('*', '/', ' ') }.filter { it.isNotBlank() }.map {
            val (name, value) = it.split("=").map { it.trim().toLowerCase() }
            Pair(name, value)
        }.toMap()
    }

    private fun ConfigurationFromContext.extractDataToMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val runConfiguration = configuration
        result["type"] = runConfiguration.type.displayName
        result["provider"] = extractProvider(this).toString()
            .replace("Run", "").replace("Configuration", "").replace("Provider", "").replace("Producer", "")
        runConfiguration.extractDataTo(result)
        return result.map { it.key.toLowerCase() to it.value.toLowerCase() }.toMap()
    }

    private fun RunConfiguration.extractDataTo(result: MutableMap<String, String>) {
        val getter = this::class.declaredMemberFunctions.first { it.name == "getPersistantData" } as KFunction<*>
        val persistentData = getter.call(this)!!
        persistentData::class.declaredMemberProperties.forEach {
            if (it.visibility == KVisibility.PUBLIC) {
                result[it.name] = (it as KProperty1<Any, *>).get(persistentData).toString()
            }
        }
    }

    private fun extractProvider(fromContext: ConfigurationFromContext): String? {
        if (fromContext !is ConfigurationFromContextImpl) return null

        val provider = ConfigurationFromContextImpl::class.memberProperties
            .first { it.name == "myConfigurationProducer" }
            .also { it.isAccessible = true }
            .get(fromContext) as? RunConfigurationProducer<*> ?: return "ERROR"
        return provider::class.simpleName
    }

    private fun setUpModulePaths(
        modulePath: String
    ) {
        val module = module(
            getTestName(true),
            moduleRootPath = modulePath
        )
        module.configureLibraries()

        runWriteAction {
            val moduleDir = LocalFileSystem.getInstance().findFileByIoFile(File(module.getModuleDir()))!!

            val outDir = moduleDir.createOrFindChildDir(this, "out")
            val srcOutDir = outDir.createOrFindChildDir(this, "production")
            val testOutDir = outDir.createOrFindChildDir(this, "test")


            PsiTestUtil.setCompilerOutputPath(module, srcOutDir.url, false)
            PsiTestUtil.setCompilerOutputPath(module, testOutDir.url, true)
        }
    }

    abstract fun Module.configureLibraries()

    override fun tearDown() {
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.clear()
        }
        super.tearDown()
    }
}


private fun VirtualFile.createOrFindChildDir(requestor: Any, name: String): VirtualFile {
    val child = findChild(name)
    return when {
        child == null -> createChildDirectory(requestor, name)
        child.isDirectory -> child
        else -> error("$child already exists and is not a directory")
    }
}

object ForTestLibraries {
    val junitJar = findPlatformJarContaining("org.junit.Test")
    val testNGJar = findPlatformJarContaining("org.testng.annotations.Test")

    private fun String.assertExists(): File {
        val file = File(this)
        if (!file.exists()) {
            throw IllegalStateException(file.toString() + " does not exist")
        }
        return file
    }

    private fun findPlatformJarContaining(classFqName: String): File {
        val classLoader = this@ForTestLibraries::class.java.classLoader
        val pathToClassFile = classLoader.getResource("${classFqName.replace('.', '/')}.class").path
        val pathToJar =  pathToClassFile.substringAfter("file:/").substringBeforeLast(".jar") + ".jar"
        return pathToJar.assertExists()
    }
}

abstract class AbstractJUnitContextRunConfigurationTest : AbstractContextRunConfigurationTest() {

    override fun Module.configureLibraries() {
        addLibrary(
            ForTestLibraries.junitJar,
            "junit"
        )
    }

    override fun getTestDataPath() = PluginTestCaseBase.getTestDataPathBase() + "/run/tests/junit/"
}

abstract class AbstractTestNGContextRunConfigurationTest : AbstractContextRunConfigurationTest() {

    override fun Module.configureLibraries() {
        addLibrary(
            ForTestLibraries.testNGJar,
            "testNG"
        )
    }

    override fun getTestDataPath() = PluginTestCaseBase.getTestDataPathBase() + "/run/tests/testng/"
}