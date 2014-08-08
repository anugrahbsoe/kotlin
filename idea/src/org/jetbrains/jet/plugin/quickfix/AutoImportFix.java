/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.plugin.quickfix;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.codeInsight.daemon.impl.ShowAutoImportPass;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.intention.HighPriorityAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor;
import org.jetbrains.jet.lang.diagnostics.Diagnostic;
import org.jetbrains.jet.lang.psi.JetExpression;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.psi.JetPsiUtil;
import org.jetbrains.jet.lang.psi.JetSimpleNameExpression;
import org.jetbrains.jet.lang.psi.psiUtil.PsiUtilPackage;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.ImportPath;
import org.jetbrains.jet.lang.resolve.lazy.KotlinCodeAnalyzer;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.plugin.JetBundle;
import org.jetbrains.jet.plugin.actions.JetAddImportAction;
import org.jetbrains.jet.plugin.caches.JetShortNamesCache;
import org.jetbrains.jet.plugin.caches.resolve.ResolvePackage;
import org.jetbrains.jet.plugin.project.ProjectStructureUtil;
import org.jetbrains.jet.plugin.project.ResolveSessionForBodies;
import org.jetbrains.jet.plugin.util.JetPsiHeuristicsUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Check possibility and perform fix for unresolved references.
 */
public class AutoImportFix extends JetHintAction<JetSimpleNameExpression> implements HighPriorityAction {

    @NotNull
    private final Collection<FqName> suggestions;

    public AutoImportFix(@NotNull JetSimpleNameExpression element) {
        super(element);
        suggestions = computeSuggestions(element);
    }

    private static Collection<FqName> computeSuggestions(@NotNull JetSimpleNameExpression element) {
        final PsiFile file = element.getContainingFile();
        if (!(file instanceof JetFile)) {
            return Collections.emptyList();
        }

        String referenceName = element.getReferencedName();
        if (element.getIdentifier() == null) {
            Name conventionName = JetPsiUtil.getConventionName(element);
            if (conventionName != null) {
                referenceName = conventionName.asString();
            }
        }

        if (referenceName.isEmpty()) {
            return Collections.emptyList();
        }

        ResolveSessionForBodies resolveSessionForBodies = ResolvePackage.getLazyResolveSession(element);

        Module module = ModuleUtilCore.findModuleForPsiElement(file);
        if (module == null) return Collections.emptyList();
        GlobalSearchScope searchScope = GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module);

        List<FqName> result = Lists.newArrayList();
        if (!isSuppressedTopLevelImportInPosition(element)) {
            result.addAll(getClassNames(referenceName, (JetFile) file, searchScope, resolveSessionForBodies));
            result.addAll(getJetTopLevelFunctions(referenceName, element, searchScope, resolveSessionForBodies, file.getProject()));
        }

        result.addAll(getJetExtensionFunctions(referenceName, element, searchScope, resolveSessionForBodies, file.getProject()));

        return Collections2.filter(result, new Predicate<FqName>() {
            @Override
            public boolean apply(@Nullable FqName fqName) {
                assert fqName != null;
                return ImportInsertHelper.needImport(new ImportPath(fqName, false), (JetFile) file);
            }
        });
    }

    private static boolean isSuppressedTopLevelImportInPosition(@NotNull JetSimpleNameExpression element) {
        return PsiUtilPackage.isImportDirectiveExpression(element) || JetPsiUtil.isSelectorInQualified(element);
    }

    private static Collection<FqName> getJetTopLevelFunctions(
            @NotNull String referenceName,
            @NotNull JetExpression context,
            @NotNull GlobalSearchScope searchScope,
            @NotNull ResolveSessionForBodies resolveSession,
            @NotNull Project project
    ) {
        JetShortNamesCache namesCache = JetShortNamesCache.getKotlinInstance(project);

        Collection<FunctionDescriptor> topLevelFunctions = namesCache.getTopLevelFunctionDescriptorsByName(
                referenceName, context, resolveSession, searchScope);

        return Sets.newHashSet(Collections2.transform(topLevelFunctions, new Function<DeclarationDescriptor, FqName>() {
            @Override
            public FqName apply(@Nullable DeclarationDescriptor declarationDescriptor) {
                assert declarationDescriptor != null;
                return DescriptorUtils.getFqNameSafe(declarationDescriptor);
            }
        }));
    }

    private static Collection<FqName> getJetExtensionFunctions(
            @NotNull final String referenceName,
            @NotNull JetSimpleNameExpression expression,
            @NotNull GlobalSearchScope searchScope,
            @NotNull ResolveSessionForBodies resolveSession,
            @NotNull Project project
    ) {
        JetShortNamesCache namesCache = JetShortNamesCache.getKotlinInstance(project);
        Collection<DeclarationDescriptor> jetCallableExtensions = namesCache.getJetCallableExtensions(
                new Condition<String>() {
                    @Override
                    public boolean value(String callableExtensionName) {
                        return callableExtensionName.equals(referenceName);
                    }
                },
                expression,
                resolveSession,
                searchScope);

        return Sets.newHashSet(Collections2.transform(jetCallableExtensions, new Function<DeclarationDescriptor, FqName>() {
            @Override
            public FqName apply(@Nullable DeclarationDescriptor declarationDescriptor) {
                assert declarationDescriptor != null;
                return DescriptorUtils.getFqNameSafe(declarationDescriptor);
            }
        }));
    }

    /*
     * Searches for possible class names in kotlin context and java facade.
     */
    public static Collection<FqName> getClassNames(@NotNull String referenceName, @NotNull JetFile file, @NotNull GlobalSearchScope searchScope, @NotNull KotlinCodeAnalyzer analyzer) {
        Set<FqName> possibleResolveNames = Sets.newHashSet();

        if (!ProjectStructureUtil.isJsKotlinModule(file)) {
            possibleResolveNames.addAll(getClassesFromCache(referenceName, searchScope, file));
        }
        else {
            possibleResolveNames.addAll(getJetClasses(referenceName, searchScope, file.getProject(), analyzer));
        }

        // TODO: Do appropriate sorting
        return Lists.newArrayList(possibleResolveNames);
    }

    private static Collection<FqName> getClassesFromCache(@NotNull String typeName, @NotNull GlobalSearchScope searchScope, @NotNull final JetFile file) {
        PsiShortNamesCache cache = getShortNamesCache(file);

        PsiClass[] classes = cache.getClassesByName(typeName, searchScope);

        Collection<PsiClass> accessibleClasses = Collections2.filter(Lists.newArrayList(classes), new Predicate<PsiClass>() {
            @Override
            public boolean apply(PsiClass psiClass) {
                assert psiClass != null;
                return JetPsiHeuristicsUtil.isAccessible(psiClass, file);
            }
        });

        return Collections2.transform(accessibleClasses, new Function<PsiClass, FqName>() {
            @Nullable
            @Override
            public FqName apply(@Nullable PsiClass javaClass) {
                assert javaClass != null;
                String qualifiedName = javaClass.getQualifiedName();
                assert qualifiedName != null;
                return new FqName(qualifiedName);
            }
        });
    }

    private static PsiShortNamesCache getShortNamesCache(@NotNull JetFile jetFile) {
        if (ProjectStructureUtil.isJsKotlinModule(jetFile)) {
            return JetShortNamesCache.getKotlinInstance(jetFile.getProject());
        }

        return PsiShortNamesCache.getInstance(jetFile.getProject());
    }

    private static Collection<FqName> getJetClasses(@NotNull final String typeName, @NotNull GlobalSearchScope searchScope, @NotNull Project project, @NotNull KotlinCodeAnalyzer resolveSession) {
        JetShortNamesCache cache = JetShortNamesCache.getKotlinInstance(project);
        Collection<ClassDescriptor> descriptors = cache.getJetClassesDescriptors(new Condition<String>() {
            @Override
            public boolean value(String s) {
                return typeName.equals(s);
            }
        }, resolveSession, searchScope);

        return Collections2.transform(descriptors, new Function<ClassDescriptor, FqName>() {
            @Override
            public FqName apply(ClassDescriptor descriptor) {
                return DescriptorUtils.getFqNameSafe(descriptor);
            }
        });
    }

    @Override
    public boolean showHint(@NotNull Editor editor) {
        if (suggestions.isEmpty()) {
            return false;
        }

        Project project = editor.getProject();
        if (project == null) {
            return false;
        }

        if (HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) {
            return false;
        }

        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            String hintText = ShowAutoImportPass.getMessage(suggestions.size() > 1, suggestions.iterator().next().asString());

            HintManager.getInstance().showQuestionHint(
                    editor, hintText,
                    element.getTextOffset(), element.getTextRange().getEndOffset(),
                    createAction(project, editor));
        }

        return true;
    }

    @Override
    @NotNull
    public String getText() {
        return JetBundle.message("import.fix");
    }

    @Override
    @NotNull
    public String getFamilyName() {
        return JetBundle.message("import.fix");
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return super.isAvailable(project, editor, file) && !suggestions.isEmpty();
    }

    @Override
    public void invoke(@NotNull final Project project, @NotNull final Editor editor, JetFile file)
            throws IncorrectOperationException {
        CommandProcessor.getInstance().runUndoTransparentAction(new Runnable() {
            @Override
            public void run() {
                createAction(project, editor).execute();
            }
        });
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }

    @NotNull
    private JetAddImportAction createAction(@NotNull Project project, @NotNull Editor editor) {
        return new JetAddImportAction(project, editor, element, suggestions);
    }

    @Nullable
    public static JetSingleIntentionActionFactory createFactory() {
        return new JetSingleIntentionActionFactory() {
            @Nullable
            @Override
            public JetIntentionAction<JetSimpleNameExpression> createAction(@NotNull Diagnostic diagnostic) {
                // There could be different psi elements (i.e. JetArrayAccessExpression), but we can fix only JetSimpleNameExpression case
                if (diagnostic.getPsiElement() instanceof JetSimpleNameExpression) {
                    JetSimpleNameExpression psiElement = (JetSimpleNameExpression) diagnostic.getPsiElement();
                    return new AutoImportFix(psiElement);
                }

                return null;
            }

            @Override
            public boolean isApplicableForCodeFragment() {
                return true;
            }
        };
    }
}
