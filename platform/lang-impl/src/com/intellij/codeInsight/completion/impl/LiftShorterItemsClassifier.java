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
package com.intellij.codeInsight.completion.impl;

import com.intellij.codeInsight.completion.CompletionLookupArranger;
import com.intellij.codeInsight.lookup.Classifier;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.util.ProcessingContext;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import gnu.trove.THashSet;
import gnu.trove.TObjectHashingStrategy;

import java.util.*;

/**
* @author peter
*/
public class LiftShorterItemsClassifier extends Classifier<LookupElement> {
  private final TreeSet<String> mySortedStrings;
  private final MultiMap<String, LookupElement> myElements;
  private final MultiMap<String, String> myPrefixes;
  private final Classifier<LookupElement> myNext;
  private final LiftingCondition myCondition;

  public LiftShorterItemsClassifier(Classifier<LookupElement> next, LiftingCondition condition) {
    myNext = next;
    myCondition = condition;
    mySortedStrings = new TreeSet<String>();
    myElements = new MultiMap<String, LookupElement>();
    myPrefixes = new MultiMap<String, String>();
  }

  @Override
  public void addElement(LookupElement element) {
    final Set<String> strings = getAllLookupStrings(element);
    for (String string : strings) {
      if (string.length() == 0) continue;

      myElements.putValue(string, element);
      mySortedStrings.add(string);
      final NavigableSet<String> after = mySortedStrings.tailSet(string, false);
      for (String s : after) {
        if (!s.startsWith(string)) {
          break;
        }
        myPrefixes.putValue(s, string);
      }

      final char first = string.charAt(0);
      final SortedSet<String> before = mySortedStrings.descendingSet().tailSet(string, false);
      for (String s : before) {
        if (s.charAt(0) != first) {
          break;
        }

        if (string.startsWith(s)) {
          myPrefixes.putValue(string, s);
        }
      }
    }
    myNext.addElement(element);
  }

  @Override
  public Iterable<LookupElement> classify(Iterable<LookupElement> source, ProcessingContext context) {
    return liftShorterElements(source, new THashSet<LookupElement>(TObjectHashingStrategy.IDENTITY), context);
  }

  private List<LookupElement> liftShorterElements(Iterable<LookupElement> source, THashSet<LookupElement> lifted, ProcessingContext context) {
    final Set<LookupElement> srcSet = new THashSet<LookupElement>(TObjectHashingStrategy.IDENTITY);
    ContainerUtil.addAll(srcSet, source);
    final Set<LookupElement> processed = new THashSet<LookupElement>(TObjectHashingStrategy.IDENTITY);

    final List<LookupElement> result = new ArrayList<LookupElement>();
    for (LookupElement element : myNext.classify(source, context)) {
      assert srcSet.contains(element) : myNext;
      if (processed.add(element)) {
        final List<String> prefixes = new SmartList<String>();
        for (String string : getAllLookupStrings(element)) {
          prefixes.addAll(myPrefixes.get(string));
        }
        Collections.sort(prefixes);
        for (String prefix : prefixes) {
          List<LookupElement> shorter = new SmartList<LookupElement>();
          for (LookupElement shorterElement : myElements.get(prefix)) {
            if (srcSet.contains(shorterElement) && myCondition.shouldLift(shorterElement, element, context) && processed.add(shorterElement)) {
              shorter.add(shorterElement);
            }
          }

          lifted.addAll(shorter);

          ContainerUtil.addAll(result, myNext.classify(shorter, context));
        }
        result.add(element);
      }
    }
    return result;
  }

  private static Set<String> getAllLookupStrings(LookupElement element) {
    return element.getAllLookupStrings();
  }

  @Override
  public void describeItems(LinkedHashMap<LookupElement, StringBuilder> map, ProcessingContext context) {
    final THashSet<LookupElement> lifted = new THashSet<LookupElement>(TObjectHashingStrategy.IDENTITY);
    liftShorterElements(new ArrayList<LookupElement>(map.keySet()), lifted, new ProcessingContext());
    if (!lifted.isEmpty()) {
      for (LookupElement element : map.keySet()) {
        final StringBuilder builder = map.get(element);
        if (builder.length() > 0) {
          builder.append(", ");
        }

        builder.append("liftShorter=").append(lifted.contains(element));
      }
    }
    myNext.describeItems(map, context);
  }

  public static class LiftingCondition {
    public boolean shouldLift(LookupElement shorterElement, LookupElement longerElement, ProcessingContext context) {
      return context.get(CompletionLookupArranger.PURE_RELEVANCE) != Boolean.TRUE;
    }
  }
}
