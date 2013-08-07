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
package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

/**
 * @author Dmitry Avdeev
 *         Date: 4/11/12
 */
public class OpenFilesActivity implements StartupActivity, DumbAware {

  @Override
  public void runActivity(@NotNull Project project) {
    final FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
    if (fileEditorManager instanceof FileEditorManagerImpl) {
      Runnable runnable = new Runnable() {
        @Override
        public void run() {
          FileEditorManagerImpl manager = (FileEditorManagerImpl)fileEditorManager;
          manager.getMainSplitters().openFiles();
          manager.initDockableContentFactory();
        }
      };
      if (Registry.is("ide.open.editors.asynchronously")) {
        runnable.run();
      }
      else {
        UIUtil.invokeLaterIfNeeded(runnable);
      }
    }
  }
}
