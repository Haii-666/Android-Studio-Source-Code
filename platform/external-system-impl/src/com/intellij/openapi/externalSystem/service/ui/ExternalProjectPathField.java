/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.openapi.externalSystem.service.ui;

import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.ExternalSystemUiAware;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.project.ExternalProjectPojo;
import com.intellij.openapi.externalSystem.service.task.ui.ExternalSystemNode;
import com.intellij.openapi.externalSystem.service.task.ui.ExternalSystemTasksTree;
import com.intellij.openapi.externalSystem.service.task.ui.ExternalSystemTasksTreeModel;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle;
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComponentWithBrowseButton;
import com.intellij.openapi.ui.FixedSizeButton;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.TextAccessor;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Consumer;
import com.intellij.util.TextFieldCompletionProvider;
import com.intellij.util.TextFieldCompletionProviderDumbAware;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.ContainerUtilRt;
import com.intellij.util.ui.GridBag;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author Denis Zhdanov
 * @since 24.05.13 19:13
 */
public class ExternalProjectPathField extends ComponentWithBrowseButton<ExternalProjectPathField.MyPathAndProjectButtonPanel>
  implements TextAccessor
{

  @NotNull private static final String PROJECT_FILE_TO_START_WITH_KEY = "external.system.task.project.file.to.start";

  @NotNull private final Project         myProject;
  @NotNull private final ProjectSystemId myExternalSystemId;

  public ExternalProjectPathField(@NotNull Project project,
                                  @NotNull ProjectSystemId externalSystemId,
                                  @NotNull FileChooserDescriptor descriptor,
                                  @NotNull String fileChooserTitle)
  {
    super(createPanel(project, externalSystemId), new MyBrowseListener(descriptor, fileChooserTitle, project));
    ActionListener[] listeners = getButton().getActionListeners();
    for (ActionListener listener : listeners) {
      if (listener instanceof MyBrowseListener) {
        ((MyBrowseListener)listener).setPathField(getChildComponent().getTextField());
        break;
      }
    }
    myProject = project;
    myExternalSystemId = externalSystemId;
  }

  @NotNull
  private static MyPathAndProjectButtonPanel createPanel(@NotNull final Project project, @NotNull final ProjectSystemId externalSystemId) {
    final EditorTextField textField = createTextField(project, externalSystemId);
    
    final FixedSizeButton selectRegisteredProjectButton = new FixedSizeButton();
    selectRegisteredProjectButton.setIcon(AllIcons.Actions.Module);
    String tooltipText = ExternalSystemBundle.message("run.configuration.tooltip.choose.registered.project",
                                                      externalSystemId.getReadableName());
    selectRegisteredProjectButton.setToolTipText(tooltipText);
    selectRegisteredProjectButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        final Ref<JBPopup> popupRef = new Ref<JBPopup>();
        final Tree tree = buildRegisteredProjectsTree(project, externalSystemId);
        tree.setBorder(IdeBorderFactory.createEmptyBorder(8));
        Runnable treeSelectionCallback = new Runnable() {
          @Override
          public void run() {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
              Object lastPathComponent = path.getLastPathComponent();
              if (lastPathComponent instanceof ExternalSystemNode) {
                Object e = ((ExternalSystemNode)lastPathComponent).getDescriptor().getElement();
                if (e instanceof ExternalProjectPojo) {
                  ExternalProjectPojo pojo = (ExternalProjectPojo)e;
                  textField.setText(pojo.getPath());
                  Editor editor = textField.getEditor();
                  if (editor != null) {
                    collapseIfPossible(editor, externalSystemId, project);
                  }
                }
              }
            }
            popupRef.get().closeOk(null); 
          }
        };
        JBPopup popup = new PopupChooserBuilder(tree)
          .setTitle(ExternalSystemBundle.message("run.configuration.title.choose.registered.project", externalSystemId.getReadableName()))
          .setResizable(true)
          .setItemChoosenCallback(treeSelectionCallback)
          .setAutoselectOnMouseMove(true)
          .setCloseOnEnter(false)
          .createPopup();
        popupRef.set(popup);
        popup.showUnderneathOf(selectRegisteredProjectButton);
      }
    });
    return new MyPathAndProjectButtonPanel(textField, selectRegisteredProjectButton);
  }

  @NotNull
  private static Tree buildRegisteredProjectsTree(@NotNull Project project, @NotNull ProjectSystemId externalSystemId) {
    ExternalSystemTasksTreeModel model = new ExternalSystemTasksTreeModel(externalSystemId);
    ExternalSystemTasksTree result = new ExternalSystemTasksTree(model, ContainerUtilRt.<String, Boolean>newHashMap(), project, externalSystemId);
    
    ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(externalSystemId);
    assert manager != null;
    AbstractExternalSystemLocalSettings settings = manager.getLocalSettingsProvider().fun(project);
    Map<ExternalProjectPojo, Collection<ExternalProjectPojo>> projects = settings.getAvailableProjects();
    List<ExternalProjectPojo> rootProjects = ContainerUtilRt.newArrayList(projects.keySet());
    ContainerUtil.sort(rootProjects);
    for (ExternalProjectPojo rootProject : rootProjects) {
      model.ensureSubProjectsStructure(rootProject, projects.get(rootProject));
    }
    return result;
  }
  
  @NotNull
  private static EditorTextField createTextField(@NotNull final Project project, @NotNull final ProjectSystemId externalSystemId) {
    ExternalSystemManager<?, ?, ?, ?, ?> manager = ExternalSystemApiUtil.getManager(externalSystemId);
    assert manager != null;
    final AbstractExternalSystemLocalSettings settings = manager.getLocalSettingsProvider().fun(project);
    final ExternalSystemUiAware uiAware = ExternalSystemUiUtil.getUiAware(externalSystemId);
    TextFieldCompletionProvider provider = new TextFieldCompletionProviderDumbAware() {
      @Override
      protected void addCompletionVariants(@NotNull String text, int offset, @NotNull String prefix, @NotNull CompletionResultSet result) {
        for (Map.Entry<ExternalProjectPojo, Collection<ExternalProjectPojo>> entry : settings.getAvailableProjects().entrySet()) {
          String rootProjectPath = entry.getKey().getPath();
          String rootProjectName = uiAware.getProjectRepresentationName(rootProjectPath, null);
          ExternalProjectPathLookupElement rootProjectElement = new ExternalProjectPathLookupElement(rootProjectName, rootProjectPath);
          result.addElement(rootProjectElement);
          for (ExternalProjectPojo subProject : entry.getValue()) {
            String p = subProject.getPath();
            if (rootProjectPath.equals(p)) {
              continue;
            }
            String subProjectName = uiAware.getProjectRepresentationName(p, rootProjectPath);
            ExternalProjectPathLookupElement subProjectElement = new ExternalProjectPathLookupElement(subProjectName, p);
            result.addElement(subProjectElement);
          }
        }
        result.stopHere();
      }
    };
    EditorTextField result = provider.createEditor(project, false, new Consumer<Editor>() {
      @Override
      public void consume(Editor editor) {
        collapseIfPossible(editor, externalSystemId, project);
        editor.getSettings().setShowIntentionBulb(false);
      }
    });
    result.setBorder(UIUtil.getTextFieldBorder());
    result.setOneLineMode(true);
    result.setOpaque(true);
    result.setBackground(UIUtil.getTextFieldBackground());
    return result;
  }
  
  @Override
  public void setText(final String text) {
    getChildComponent().getTextField().setText(text);

    Editor editor = getChildComponent().getTextField().getEditor();
    if (editor != null) {
      collapseIfPossible(editor, myExternalSystemId, myProject);
    }
  }

  private static void collapseIfPossible(@NotNull final Editor editor,
                                         @NotNull ProjectSystemId externalSystemId,
                                         @NotNull Project project)
  {
    ExternalSystemManager<?,?,?,?,?> manager = ExternalSystemApiUtil.getManager(externalSystemId);
    assert manager != null;
    final AbstractExternalSystemLocalSettings settings = manager.getLocalSettingsProvider().fun(project);
    final ExternalSystemUiAware uiAware = ExternalSystemUiUtil.getUiAware(externalSystemId);

    String rawText = editor.getDocument().getText();
    for (Map.Entry<ExternalProjectPojo, Collection<ExternalProjectPojo>> entry : settings.getAvailableProjects().entrySet()) {
      if (entry.getKey().getPath().equals(rawText)) {
        collapse(editor, uiAware.getProjectRepresentationName(entry.getKey().getPath(), null));
        return;
      }
      for (ExternalProjectPojo pojo : entry.getValue()) {
        if (pojo.getPath().equals(rawText)) {
          collapse(editor, uiAware.getProjectRepresentationName(pojo.getPath(), entry.getKey().getPath()));
          return;
        }
      }
    }
  }

  private static void collapse(@NotNull final Editor editor, @NotNull final String placeholder) {
    final FoldingModel foldingModel = editor.getFoldingModel();
    foldingModel.runBatchFoldingOperation(new Runnable() {
      @Override
      public void run() {
        for (FoldRegion region : foldingModel.getAllFoldRegions()) {
          foldingModel.removeFoldRegion(region);
        }
        FoldRegion region = foldingModel.addFoldRegion(0, editor.getDocument().getTextLength(), placeholder);
        if (region != null) {
          region.setExpanded(false);
        }
      }
    });
  }

  @Override
  public String getText() {
    return getChildComponent().getTextField().getText();
  }
  
  private static class MyBrowseListener implements ActionListener {
    
    @NotNull private final FileChooserDescriptor myDescriptor;
    @NotNull private final Project myProject;
    private EditorTextField myPathField;
    
    MyBrowseListener(@NotNull final FileChooserDescriptor descriptor,
                     @NotNull final String fileChooserTitle,
                     @NotNull final Project project)
    {
      descriptor.setTitle(fileChooserTitle);
      myDescriptor = descriptor;
      myProject = project;
    }

    private void setPathField(@NotNull EditorTextField pathField) {
      myPathField = pathField;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      if (myPathField == null) {
        assert false;
        return;
      }
      PropertiesComponent component = PropertiesComponent.getInstance(myProject);
      String pathToStart = myPathField.getText();
      if (StringUtil.isEmpty(pathToStart)) {
        pathToStart = component.getValue(PROJECT_FILE_TO_START_WITH_KEY);
      }
      VirtualFile fileToStart = null;
      if (!StringUtil.isEmpty(pathToStart)) {
        fileToStart = LocalFileSystem.getInstance().findFileByPath(pathToStart);
      }
      VirtualFile file = FileChooser.chooseFile(myDescriptor, myProject, fileToStart);
      if (file != null) {
        String path = ExternalSystemApiUtil.getLocalFileSystemPath(file);
        myPathField.setText(path);
        component.setValue(PROJECT_FILE_TO_START_WITH_KEY, path);
      }
    }
  }
  
  public static class MyPathAndProjectButtonPanel extends JPanel {

    @NotNull private final EditorTextField myTextField;
    @NotNull private final FixedSizeButton myRegisteredProjectsButton;

    public MyPathAndProjectButtonPanel(@NotNull EditorTextField textField,
                                       @NotNull FixedSizeButton registeredProjectsButton)
    {
      super(new GridBagLayout());
      myTextField = textField;
      myRegisteredProjectsButton = registeredProjectsButton;
      add(myTextField, new GridBag().weightx(1).fillCellHorizontally());
      add(myRegisteredProjectsButton, new GridBag().insets(0, 3, 0, 0));
    }

    @NotNull
    public EditorTextField getTextField() {
      return myTextField;
    }
  }
}
