/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.util.indexing;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

/**
 * @author yole
 */
public class IndexingDataKeys {
  public static final Key<VirtualFile> VIRTUAL_FILE = new Key<VirtualFile>("Context virtual file");
  public static final Key<Project> PROJECT = new Key<Project>("Context project");
  public static final Key<PsiFile> PSI_FILE = new Key<PsiFile>("PSI for stubs");

  private IndexingDataKeys() {
  }
}