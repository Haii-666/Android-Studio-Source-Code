/*
 * Copyright 2000-2012 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 26-Dec-2007
 */
package com.intellij.openapi.roots.ui;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.ui.SdkPathEditor;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.KeyedExtensionFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public interface OrderRootTypeUIFactory {
  KeyedExtensionFactory<OrderRootTypeUIFactory, OrderRootType> FACTORY = new KeyedExtensionFactory<OrderRootTypeUIFactory, OrderRootType>(OrderRootTypeUIFactory.class, "com.intellij.OrderRootTypeUI") {
    @Override
    public String getKey(@NotNull final OrderRootType key) {
      return key.name();
    }
  };

  SdkPathEditor createPathEditor(Sdk sdk);

  Icon getIcon();

  String getNodeText();
}
