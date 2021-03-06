// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.components;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.firebase.inject.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ComponentRuntimeTest {
  private static final Executor EXECUTOR = Runnable::run;

  interface ComponentOne {
    InitTracker getTracker();
  }

  interface ComponentTwo {
    ComponentOne getOne();
  }

  private static class ComponentOneImpl implements ComponentOne {
    private final InitTracker tracker;

    ComponentOneImpl(InitTracker tracker) {
      this.tracker = tracker;
      tracker.initialize();
    }

    @Override
    public InitTracker getTracker() {
      return tracker;
    }
  }

  private static class ComponentTwoImpl implements ComponentTwo {
    private final ComponentOne one;

    ComponentTwoImpl(ComponentOne one) {
      this.one = one;
    }

    @Override
    public ComponentOne getOne() {
      return one;
    }
  }

  private static class ComponentRegistrarImpl implements ComponentRegistrar {
    @Override
    public List<Component<?>> getComponents() {
      return Arrays.asList(
          Component.builder(ComponentTwo.class)
              .add(Dependency.required(ComponentOne.class))
              .factory(container -> new ComponentTwoImpl(container.get(ComponentOne.class)))
              .build(),
          Component.builder(ComponentOne.class)
              .add(Dependency.required(InitTracker.class))
              .alwaysEager()
              .factory(container -> new ComponentOneImpl(container.get(InitTracker.class)))
              .build());
    }
  }

  @Test
  public void
      container_withValidDependencyGraph_shouldInitializeEagerComponentsAndTheirDependencies() {
    InitTracker initTracker = new InitTracker();

    ComponentRuntime runtime =
        new ComponentRuntime(
            EXECUTOR,
            Collections.singletonList(new ComponentRegistrarImpl()),
            Component.of(initTracker, InitTracker.class));

    assertThat(initTracker.isInitialized()).isFalse();

    runtime.initializeEagerComponents(true);

    assertThat(initTracker.isInitialized()).isTrue();
  }

  @Test
  public void container_withValidDependencyGraph_shouldProperlyInjectComponents() {
    InitTracker initTracker = new InitTracker();

    ComponentRuntime runtime =
        new ComponentRuntime(
            EXECUTOR,
            Collections.singletonList(new ComponentRegistrarImpl()),
            Component.of(initTracker, InitTracker.class));

    assertThat(initTracker.isInitialized()).isFalse();

    ComponentTwo componentTwo = runtime.get(ComponentTwo.class);
    assertThat(componentTwo.getOne()).isNotNull();
    assertThat(componentTwo.getOne().getTracker()).isSameAs(initTracker);

    assertThat(initTracker.isInitialized()).isTrue();
  }

  @Test
  public void container_withCyclicDependencyGraph_shouldThrow() {
    try {
      new ComponentRuntime(
          EXECUTOR,
          Collections.singletonList(new ComponentRegistrarImpl()),
          Component.builder(InitTracker.class)
              .add(Dependency.required(ComponentTwo.class))
              .factory(container -> null)
              .build());
      fail("Expected exception not thrown.");
    } catch (DependencyCycleException ex) {
      // success.
    }
  }

  @Test
  public void container_withMultipleComponentsRegisteredForSameInterface_shouldThrow() {
    try {
      new ComponentRuntime(
          EXECUTOR,
          Collections.singletonList(new ComponentRegistrarImpl()),
          Component.builder(ComponentOne.class).factory(container -> null).build());
      fail("Expected exception not thrown.");
    } catch (IllegalArgumentException ex) {
      // success.
    }
  }

  @Test
  public void container_withMissingDependencies_shouldThrow() {
    try {
      new ComponentRuntime(EXECUTOR, Collections.singletonList(new ComponentRegistrarImpl()));
      fail("Expected exception not thrown.");
    } catch (MissingDependencyException ex) {
      // success.
    }
  }

  private static class CyclicOne {
    final CyclicTwo cyclicTwo;

    CyclicOne(CyclicTwo two) {
      cyclicTwo = two;
    }
  }

  private static class CyclicTwo {
    final Provider<CyclicOne> cyclicOne;

    CyclicTwo(Provider<CyclicOne> one) {
      cyclicOne = one;
    }
  }

  @Test
  public void container_withCyclicProviderDependency_shouldProperlyInitialize() {
    ComponentRuntime runtime =
        new ComponentRuntime(
            EXECUTOR,
            Collections.emptyList(),
            Component.builder(CyclicOne.class)
                .add(Dependency.required(CyclicTwo.class))
                .factory(container -> new CyclicOne(container.get(CyclicTwo.class)))
                .build(),
            Component.builder(CyclicTwo.class)
                .add(Dependency.requiredProvider(CyclicOne.class))
                .factory(container -> new CyclicTwo(container.getProvider(CyclicOne.class)))
                .build());
    CyclicOne one = runtime.get(CyclicOne.class);

    assertThat(one.cyclicTwo).isNotNull();
    Provider<CyclicOne> oneProvider = one.cyclicTwo.cyclicOne;
    assertThat(oneProvider).isNotNull();
    assertThat(oneProvider.get()).isSameAs(one);
  }

  @Test
  public void get_withNullInterface_shouldThrow() {
    ComponentRuntime runtime = new ComponentRuntime(EXECUTOR, new ArrayList<>());
    try {
      runtime.get(null);
      fail("Expected exception not thrown.");
    } catch (NullPointerException ex) {
      // success.
    }
  }

  @Test
  public void get_withMissingInterface_shouldReturnNull() {
    ComponentRuntime runtime = new ComponentRuntime(EXECUTOR, new ArrayList<>());
    assertThat(runtime.get(List.class)).isNull();
  }

  private interface Parent {}

  private static class Child implements Parent {}

  @Test
  public void container_shouldExposeAllProvidedInterfacesOfAComponent() {
    ComponentRuntime runtime =
        new ComponentRuntime(
            EXECUTOR,
            Collections.emptyList(),
            Component.builder(Child.class, Parent.class).factory(c -> new Child()).build());

    Provider<Child> child = runtime.getProvider(Child.class);
    assertThat(child).isNotNull();
    Provider<Parent> parent = runtime.getProvider(Parent.class);
    assertThat(parent).isNotNull();

    assertThat(child).isSameAs(parent);
    assertThat(child.get()).isSameAs(parent.get());
  }
}
