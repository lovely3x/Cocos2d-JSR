package com.lovely3x.jsr.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.lovely3x.jsr.listener.ProjectFileChangeListener;

/**
 * Created by lovely3x on 2017/6/19.
 */
public class GenerateAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            ProjectFileChangeListener generator = project.
                    getUserData(ProjectFileChangeListener.USER_DATA_KEY_JSR_GENERATOR);
            if (generator == null) {
                generator = new ProjectFileChangeListener(project);
            }
            generator.updateConfiguration();
            generator.generateSourceFile(true);
        }
    }

}
