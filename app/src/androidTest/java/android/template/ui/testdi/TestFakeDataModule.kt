/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.template.ui.testdi

import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import android.template.core.data.MyModelRepository
import android.template.core.data.di.DataModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DataModule::class]
)
interface TestFakeDataModule {

    @Singleton
    @Binds
    fun bindRepository(fakeRepository: TestFakeMyModelRepository): MyModelRepository
}

/**
 * 测试用 Fake Repository，支持 add() 操作（原 FakeMyModelRepository 的 add 会抛异常）
 */
@Singleton
class TestFakeMyModelRepository @Inject constructor() : MyModelRepository {

    private val items = MutableStateFlow(listOf("One", "Two", "Three"))

    override val myModels: Flow<List<String>> = items

    override suspend fun add(name: String) {
        items.value = items.value + name
    }
}
