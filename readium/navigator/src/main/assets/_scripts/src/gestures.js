/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

import { handleDecorationClickEvent } from "./decorator";
import { getCurrentSelection } from "./selection";

function debounce(delay, func) {
  var timeout;
  return function () {
    var self = this;
    var args = arguments;
    function callback() {
      func.apply(self, args);
      timeout = null;
    }
    clearTimeout(timeout);
    timeout = setTimeout(callback, delay);
  };
}

window.addEventListener("DOMContentLoaded", function () {
  document.addEventListener("click", onClick, false);
  document.addEventListener('contextmenu', function(event) {
    // Prevent the context menu from appearing
    event.preventDefault();
  });
  document.addEventListener(
    "selectionchange",
    debounce(50, function () {
      getCurrentSelection()
    }),
    false
  );
  bindDragGesture(document);
});


function onClick(event) {
  if (!window.getSelection().isCollapsed) {
    // There's an on-going selection, the tap will dismiss it so we don't forward it.
    return;
  }
  var pixelRatio = window.devicePixelRatio;
  const nearest = nearestInteractiveElement(event.target);
  let clickEvent = {
    defaultPrevented: event.defaultPrevented,
    x: event.screenX * pixelRatio,
    y: event.screenY * pixelRatio,
    targetElement: event.target.outerHTML,
    interactiveElement: nearest?nearest.html:null,
    href: nearest?nearest.href:null
  };

  if (handleDecorationClickEvent(event, clickEvent)) {
    return;
  }

  // Send the tap data over the JS bridge even if it's been handled within the web view, so that
  // it can be preserved and used by the toolkit if needed.
  var shouldPreventDefault = Android.onTap(JSON.stringify(clickEvent));

  if (shouldPreventDefault) {
    event.stopPropagation();
    event.preventDefault();
  }
}

function bindDragGesture(element) {
  // passive: false is necessary to be able to prevent the default behavior.
  element.addEventListener("touchstart", onStart, { passive: false });
  element.addEventListener("touchend", onEnd, { passive: false });
  element.addEventListener("touchmove", onMove, { passive: false });

  var state = undefined;
  var isStartingDrag = false;
  const pixelRatio = window.devicePixelRatio;

  function onStart(event) {
    isStartingDrag = true;

    const startX = event.touches[0].clientX * pixelRatio;
    const startY = event.touches[0].clientY * pixelRatio;
    const nearest = nearestInteractiveElement(event.target);
    state = {
      defaultPrevented: event.defaultPrevented,
      startX: startX,
      startY: startY,
      currentX: startX,
      currentY: startY,
      offsetX: 0,
      offsetY: 0,
      interactiveElement: nearest?nearest.html:null
    };
  }

  function onMove(event) {
    if (!state) return;

    state.currentX = event.touches[0].clientX * pixelRatio;
    state.currentY = event.touches[0].clientY * pixelRatio;
    state.offsetX = state.currentX - state.startX;
    state.offsetY = state.currentY - state.startY;

    var shouldPreventDefault = false;
    // Wait for a movement of at least 6 pixels before reporting a drag.
    if (isStartingDrag) {
      if (Math.abs(state.offsetX) >= 6 || Math.abs(state.offsetY) >= 6) {
        isStartingDrag = false;
        shouldPreventDefault = Android.onDragStart(JSON.stringify(state));
      }
    } else {
      shouldPreventDefault = Android.onDragMove(JSON.stringify(state));
    }

    if (shouldPreventDefault) {
      event.stopPropagation();
      event.preventDefault();
    }
  }

  function onEnd(event) {
    if (!state) return;

    const shouldPreventDefault = Android.onDragEnd(JSON.stringify(state));
    if (shouldPreventDefault) {
      event.stopPropagation();
      event.preventDefault();
    }
    state = undefined;
  }
}

// See. https://github.com/JayPanoz/architecture/tree/touch-handling/misc/touch-handling
function nearestInteractiveElement(element) {
  var interactiveTags = [
    "a",
    "audio",
    "button",
    "canvas",
    "details",
    "input",
    "label",
    "option",
    "select",
    "submit",
    "textarea",
    "video",
  ];
  if (interactiveTags.indexOf(element.nodeName.toLowerCase()) != -1) {
    if (element.nodeName.toLowerCase() == 'a' && element.hasAttribute('href')) {
      return {
	html: element.outerHTML,
	href: new URL(element.getAttribute('href'), document.location.href)
      }
    }
  }

  // Checks whether the element is editable by the user.
  if (
    element.hasAttribute("contenteditable") &&
    element.getAttribute("contenteditable").toLowerCase() != "false"
  ) {
    return {
      html: element.outerHTML,
      href: null
    }
  }

  // Checks parents recursively because the touch might be for example on an <em> inside a <a>.
  if (element.parentElement) {
    return nearestInteractiveElement(element.parentElement);
  }

  return null;
}
