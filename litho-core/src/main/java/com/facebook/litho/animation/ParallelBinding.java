/*
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */


package com.facebook.litho.animation;

import com.facebook.litho.dataflow.ChoreographerCompat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link AnimationBinding} that's composed of other {@link AnimationBinding}s running in
 * parallel, possibly starting on a stagger.
 */
public class ParallelBinding implements AnimationBinding {

  private final CopyOnWriteArrayList<AnimationBindingListener> mListeners =
      new CopyOnWriteArrayList<>();
  private final List<AnimationBinding> mBindings;
  private final AnimationBindingListener mChildListener;
  private final HashSet<AnimationBinding> mBindingsFinished = new HashSet<>();
  private final ChoreographerCompat.FrameCallback mStaggerCallback;
  private final int mStaggerMs;
  private int mNextIndexToStart = 0;
  private int mChildrenFinished = 0;
  private boolean mHasStarted = false;
  private boolean mIsActive = false;
  private Resolver mResolver;

  public ParallelBinding(int staggerMs, List<AnimationBinding> bindings) {
    mStaggerMs = staggerMs;
    mBindings = bindings;

    if (mBindings.isEmpty()) {
      throw new IllegalArgumentException("Empty binding parallel");
    }

    mChildListener = new AnimationBindingListener() {
      @Override
      public void onWillStart(AnimationBinding binding) {
      }

      @Override
      public void onFinish(AnimationBinding binding) {
        ParallelBinding.this.onBindingFinished(binding);
      }

      @Override
      public void onCanceledBeforeStart(AnimationBinding binding) {
        ParallelBinding.this.onBindingFinished(binding);
      }

      @Override
      public boolean shouldStart(AnimationBinding binding) {
        return true;
      }
    };

    if (mStaggerMs == 0) {
      mStaggerCallback = null;
    } else {
      mStaggerCallback = new ChoreographerCompat.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!mIsActive) {
            return;
          }
          startNextBindingForStagger();
        }
      };
    }
  }

  private void onBindingFinished(AnimationBinding binding) {
    if (mBindingsFinished.contains(binding)) {
      throw new RuntimeException("Binding unexpectedly completed twice");
    }
    mBindingsFinished.add(binding);
    mChildrenFinished++;
    binding.removeListener(mChildListener);

    if (mChildrenFinished >= mBindings.size()) {
      finish();
    }
  }

  private void finish() {
    mIsActive = false;
    for (AnimationBindingListener listener : mListeners) {
      listener.onFinish(this);
    }
  }

  @Override
  public void start(Resolver resolver) {
    if (mHasStarted) {
      throw new RuntimeException("Starting binding multiple times");
    }
    mHasStarted = true;
    mResolver = resolver;

    for (AnimationBindingListener listener : mListeners) {
      if (!listener.shouldStart(this)) {
        notifyCanceledBeforeStart();
        return;
      }
    }
    for (AnimationBindingListener listener : mListeners) {
      listener.onWillStart(this);
    }

    mIsActive = true;

    for (AnimationBinding binding : mBindings) {
      binding.addListener(mChildListener);
    }
    if (mStaggerMs == 0) {
      for (int i = 0, size = mBindings.size(); i < size; i++) {
        final AnimationBinding binding = mBindings.get(i);
        binding.start(mResolver);
      }
      mNextIndexToStart = mBindings.size();
    } else {
      startNextBindingForStagger();
    }
  }

  private void startNextBindingForStagger() {
    mBindings.get(mNextIndexToStart).start(mResolver);
    mNextIndexToStart++;

    if (mNextIndexToStart < mBindings.size()) {
      ChoreographerCompat.getInstance().postFrameCallbackDelayed(mStaggerCallback, mStaggerMs);
    }
  }

  private void notifyCanceledBeforeStart() {
    for (AnimationBindingListener listener : mListeners) {
      listener.onCanceledBeforeStart(this);
    }
  }

  @Override
  public void stop() {
    if (!mIsActive) {
      return;
    }
    mIsActive = false;
    mResolver = null;
    for (int i = 0, size = mBindings.size(); i < size; i++) {
      final AnimationBinding childBinding = mBindings.get(i);
      if (childBinding.isActive()) {
        childBinding.stop();
      }
    }
  }

  @Override
  public boolean isActive() {
    return mIsActive;
  }

  @Override
  public void collectTransitioningProperties(ArrayList<PropertyAnimation> outList) {
    for (int i = 0, size = mBindings.size(); i < size; i++) {
      mBindings.get(i).collectTransitioningProperties(outList);
    }
  }

  @Override
  public void addListener(AnimationBindingListener animationBindingListener) {
    mListeners.add(animationBindingListener);
  }

  @Override
  public void removeListener(AnimationBindingListener animationBindingListener) {
    mListeners.remove(animationBindingListener);
  }
}
