//
//  Copyright 2021 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//
import { EpubCFI } from "./epub-cfi";
import { TextQuoteAnchor } from "./vendor/hypothesis/anchoring/types";

// Catch JS errors to log them in the app.
window.addEventListener(
  "error",
  function (event) {
    Android.logError(event.message, event.filename, event.lineno);
  },
  false
);

window.addEventListener(
  "load",
  function () {
    const observer = new ResizeObserver(() => {
      appendVirtualColumnIfNeeded();
    });
    observer.observe(document.body);

    window.addEventListener("onSizeChanged", function () {
      onViewportWidthChanged();
    });

    window.addEventListener("orientationchange", function () {
      snapCurrentOffset();
    });
    observer.observe(document.body);
  },
  false
);

/**
 * Having an odd number of columns when displaying two columns per screen causes snapping and page
 * turning issues. To fix this, we insert a blank virtual column at the end of the resource.
 */
function appendVirtualColumnIfNeeded() {
  const id = "readium-virtual-page";
  var virtualCol = document.getElementById(id);
  if (isScrollModeEnabled() || getColumnCountPerScreen() != 2) {
    if (virtualCol) {
      virtualCol.remove();
    }
  } else {
    var documentWidth = document.scrollingElement.scrollWidth;
    var colCount = documentWidth / pageWidth;
    var hasOddColCount = (Math.round(colCount * 2) / 2) % 1 > 0.1;
    if (hasOddColCount) {
      if (virtualCol) {
        virtualCol.remove();
      } else {
        virtualCol = document.createElement("div");
        virtualCol.setAttribute("id", id);
        virtualCol.style.breakBefore = "column";
        virtualCol.innerHTML = "&#8203;"; // zero-width space
        document.body.appendChild(virtualCol);
      }
    }
  }
}

export var pageWidth = 1;

function onViewportWidthChanged() {
  // We can't rely on window.innerWidth for the pageWidth on Android, because if the
  // device pixel ratio is not an integer, we get rounding issues offsetting the pages.
  //
  // See https://github.com/readium/readium-css/issues/97
  // and https://github.com/readium/r2-navigator-kotlin/issues/146
  var width = Android.getViewportWidth();
  pageWidth = width / window.devicePixelRatio;
  setProperty(
    "--RS__viewportWidth",
    "calc(" + width + "px / " + window.devicePixelRatio + ")"
  );

  appendVirtualColumnIfNeeded();
}

export function getColumnCountPerScreen() {
  return parseInt(window.getComputedStyle(document.documentElement).getPropertyValue("column-count"));
}

export function isScrollModeEnabled() {
  const style = document.documentElement.style;
  return (
    style.getPropertyValue("--USER__view").trim() == "readium-scroll-on" ||
    // FIXME: Will need to be removed in Readium 3.0, --USER__scroll was incorrect.
    style.getPropertyValue("--USER__scroll").trim() == "readium-scroll-on"
  );
}

export function isRTL() {
  return document.body.dir.toLowerCase() == "rtl";
}

// Scroll to the given TagId in document and snap.
export function scrollToId(id) {
  var element = document.getElementById(id);
  if (!element) {
    return false;
  }

  return scrollToRect(element.getBoundingClientRect());
}

// Position must be in the range [0 - 1], 0-100%.
export function scrollToPosition(position) {
  //        Android.log("scrollToPosition " + position);
  if (position < 0 || position > 1) {
    throw "scrollToPosition() must be given a position from 0.0 to  1.0";
  }

  let offset;
  if (isScrollModeEnabled()) {
    offset = document.scrollingElement.scrollHeight * position;
    document.scrollingElement.scrollTop = offset;
    // window.scrollTo(0, offset);
  } else {
    var documentWidth = document.scrollingElement.scrollWidth;
    var factor = isRTL() ? -1 : 1;
    offset = documentWidth * position * factor;
    document.scrollingElement.scrollLeft = snapOffset(offset);
  }
}

export function scrollToElement(element, textPosition) {
  // console.log("ScrollToElement " + element.tagName + (textPosition ? " (offset: " + textPosition + ")" : ""));
  var windowWidth = window.innerWidth;
  var elementScreenLeftOffset = element.getBoundingClientRect().left;

  if (window.scrollX % windowWidth === 0 && elementScreenLeftOffset >= 0 && elementScreenLeftOffset <= windowWidth) {
    // console.log("ScrollToElement ignored");
    return;
  }

  var page = getPageForElement(element, elementScreenLeftOffset, textPosition);
  scrollToOffset(page * windowWidth);
}

export function scrollToPartialCfi(partialCfi) {
  // console.log("ScrollToPartialCfi " + partialCfi);
  var epubCfi = new EpubCFI("epubcfi(/6/2!" + partialCfi + ")");
  var element = document.querySelector(epubCfi.generateHtmlQuery());
  if (element) {
    var textPosition = parseInt(EpubCFI.getCharacterOffsetComponent(partialCfi), 10);
    scrollToElement(element, textPosition);
  } else {
    console.log("Partial CFI element not found");
  }
}

function getPageForElement(element, elementScreenLeftOffset, textOffset) {
  if (!textOffset) {
    return Math.ceil((window.scrollX + elementScreenLeftOffset) / window.innerWidth) - 1;
  }

  const position = textOffset / element.textContent.length;
  const rects = Array.from(element.getClientRects()).map(function (rect) {
    return {
      rect,
      offset: Math.floor((window.scrollX + rect.left) / window.innerWidth),
      surface: rect.width * rect.height,
    };
  });
  const textTotalSurface = rects.reduce(function (total, current) {
    return total + current.surface;
  }, 0);
  // console.log('[getPageForElement]', {element, position, rects, textTotalSurface});

  const rectToDisplay = rects
    .map(function (rect, index) {
      if (index === 0) {
        rect.start = 0;
        rect.end = rect.surface / textTotalSurface;
      } else {
        rect.start = rects[index - 1].end;
        rect.end = rect.start + rect.surface / textTotalSurface;
      }
      return rect;
    })
    .find(function (rect) {
      return position >= rect.start && position < rect.end;
    });
  // console.log('[getPageForElement] rectToDisplay', rectToDisplay);

  return rectToDisplay ? rectToDisplay.offset : 0;
}

// Scrolls to the first occurrence of the given text snippet.
//
// The expected text argument is a Locator Text object, as defined here:
// https://readium.org/architecture/models/locators/
export function scrollToText(text) {
  let range = rangeFromLocator({ text });
  if (!range) {
    return false;
  }
  scrollToRange(range);
  return true;
}

function scrollToRange(range) {
  return scrollToRect(range.getBoundingClientRect());
}

function scrollToRect(rect) {
  if (isScrollModeEnabled()) {
    document.scrollingElement.scrollTop = rect.top + window.scrollY - window.innerHeight / 2;
  } else {
    document.scrollingElement.scrollLeft = snapOffset(rect.left + window.scrollX);
  }

  return true;
}

export function scrollToStart() {
  //        Android.log("scrollToStart");
  if (!isScrollModeEnabled()) {
    document.scrollingElement.scrollLeft = 0;
  } else {
    document.scrollingElement.scrollTop = 0;
    window.scrollTo(0, 0);
  }
}

export function scrollToEnd() {
  //        Android.log("scrollToEnd");
  if (!isScrollModeEnabled()) {
    var factor = isRTL() ? -1 : 1;
    document.scrollingElement.scrollLeft = snapOffset(document.scrollingElement.scrollWidth * factor);
  } else {
    document.scrollingElement.scrollTop = document.body.scrollHeight;
    window.scrollTo(0, document.body.scrollHeight);
  }
}

// Returns false if the page is already at the left-most scroll offset.
export function scrollLeft() {
  var documentWidth = document.scrollingElement.scrollWidth;
  var offset = window.scrollX - pageWidth;
  var minOffset = isRTL() ? -(documentWidth - pageWidth) : 0;
  return scrollToOffset(Math.max(offset, minOffset));
}

// Returns false if the page is already at the right-most scroll offset.
export function scrollRight() {
  var documentWidth = document.scrollingElement.scrollWidth;
  var offset = window.scrollX + pageWidth;
  var maxOffset = isRTL() ? 0 : documentWidth - pageWidth;
  return scrollToOffset(Math.min(offset, maxOffset));
}

// Scrolls to the given left offset.
// Returns false if the page scroll position is already close enough to the given offset.
function scrollToOffset(offset) {
  // console.log("scrollToOffset " + offset);
  if (isScrollModeEnabled()) {
    throw "Called scrollToOffset() with scroll mode enabled. This can only be used in paginated mode.";
  }

  var currentOffset = window.scrollX;
  document.scrollingElement.scrollLeft = snapOffset(offset);
  // In some case the scrollX cannot reach the position respecting to innerWidth
  var diff = Math.abs(currentOffset - offset) / pageWidth;
  return diff > 0.01;
}

// Snap the offset to the screen width (page width).
function snapOffset(offset) {
  var value = offset + (isRTL() ? -1 : 1);
  return value - (value % pageWidth);
}

// Snaps the current offset to the page width.
export function snapCurrentOffset() {
  //        Android.log("snapCurrentOffset");
  if (isScrollModeEnabled()) {
    return;
  }
  var currentOffset = window.scrollX;
  // Adds half a page to make sure we don't snap to the previous page.
  var factor = isRTL() ? -1 : 1;
  var delta = factor * (pageWidth / 2);
  document.scrollingElement.scrollLeft = snapOffset(currentOffset + delta);
}

export function rangeFromLocator(locator) {
  try {
    let locations = locator.locations;
    let text = locator.text;
    if (text && text.highlight) {
      var root;
      if (locations && locations.cssSelector) {
        root = document.querySelector(locations.cssSelector);
      }
      if (!root) {
        root = document.body;
      }

      let anchor = new TextQuoteAnchor(root, text.highlight, {
        prefix: text.before,
        suffix: text.after,
      });
      return anchor.toRange();
    }

    if (locations) {
      var element = null;

      if (!element && locations.cssSelector) {
        element = document.querySelector(locations.cssSelector);
      }

      if (!element && locations.fragments) {
        for (const htmlId of locations.fragments) {
          element = document.getElementById(htmlId);
          if (element) {
            break;
          }
        }
      }

      if (element) {
        let range = document.createRange();
        range.setStartBefore(element);
        range.setEndAfter(element);
        return range;
      }
    }
  } catch (e) {
    logError(e);
  }

  return null;
}

function getFrameRect() {
  return {
    left: 0,
    right: window.innerWidth,
    top: 0,
    bottom: window.innerHeight,
  };
}

// Generate and returns the first and last visible elements partial CFIs, and visible text
export function getExtraLocationInfos() {
  if (Date.now() - (readium.lastCfiGeneration || 0) > 100) {
    const extraLocationInfos = processExtraLocationInfos(getFrameRect());

    readium.startCfi = extraLocationInfos.cfis.startCfi;
    readium.endCfi = extraLocationInfos.cfis.endCfi;
    readium.visibleText = extraLocationInfos.visibleText;
    readium.lastCfiGeneration = Date.now();
  }

  return {
    cfis: {
      startCfi: readium.startCfi,
      endCfi: readium.endCfi,
    },
    visibleText: readium.visibleText,
  };
}

/// Epub CFI

function isValidTextNode(node) {
  if (!node) {
    return false;
  }
  if (node.nodeType === Node.TEXT_NODE) {
    return isValidTextNodeContent(node.nodeValue);
  }

  return false;
}

function isValidTextNodeContent(text) {
  // Heuristic to find a text node with actual text
  // If we don't do this, we may get a reference to a node that doesn't get rendered
  // (such as for example a node that has tab character and a bunch of spaces)
  // this is would be bad! ask me why.
  return !!text.trim().length;
}

function isRectVisible(rect, frameRect) {
  // Text nodes without printable text don't have client rectangles
  if (!rect) {
    return false;
  }
  // Sometimes we get client rects that are "empty" and aren't supposed to be visible
  if (rect.left === 0 && rect.right === 0 && rect.top === 0 && rect.bottom === 0) {
    return false;
  }

  return intersectRect(rect, frameRect);
}

function getTextVisibleRatio(textNode, frameRect) {
  const range = document.createRange();
  range.selectNode(textNode);
  let textTotalSurface = 0,
    textVisibleSurface = 0;
  Array.from(range.getClientRects()).forEach((current) => {
    const surface = current.width * current.height;
    textTotalSurface += surface;
    if (intersectRect(current, frameRect)) {
      textVisibleSurface += surface;
    }
  });

  return textVisibleSurface / textTotalSurface;
}

function isNodeElementVisible(node, frameRect) {
  if (node.nodeType === Node.TEXT_NODE) {
    const range = document.createRange();
    range.selectNode(node);
    const clientRectList = range.getClientRects();
    return Array.from(clientRectList).some((rect) => isRectVisible(rect, frameRect));
  } else {
    const elementRect = node.getBoundingClientRect();
    return isRectVisible(elementRect, frameRect);
  }
}

function intersectRect(r1, r2) {
  return !(r2.left >= r1.right || r2.right <= r1.left || r2.top >= r1.bottom || r2.bottom <= r1.top);
}

function findVisibleElements(viewport) {
  const bodyElement = document.body;

  if (!bodyElement) {
    return null;
  }

  const visibleElements = [];

  const treeWalker = document.createTreeWalker(
    bodyElement,
    NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
    function (node) {
      if (node.nodeType === Node.TEXT_NODE && !isValidTextNode(node)) {
        return NodeFilter.FILTER_REJECT;
      }

      return NodeFilter.FILTER_ACCEPT;
    },
    false
  );

  while (treeWalker.nextNode()) {
    const node = treeWalker.currentNode;
    if (isNodeElementVisible(node, viewport)) {
      visibleElements.push(node);
    }
  }

  return visibleElements;
}

export function processExtraLocationInfos(viewport) {
  const elements = findVisibleElements(viewport);

  const extraLocationInfos = {
    cfis: null,
    visibleText: null,
  };

  if (elements.length === 0) {
    return extraLocationInfos;
  }

  extraLocationInfos.cfis = getFirstAndLastVisiblePartialCfis(viewport, elements);
  extraLocationInfos.visibleText = getVisibleText(viewport, elements);

  return extraLocationInfos;
}

function getVisibleText(viewport, elements) {
  const textNodes = elements.filter((el) => el.nodeType === Node.TEXT_NODE);
  let fullVisibleText;

  if (textNodes.length === 0) return "";

  const firstTextNode = textNodes[0];

  // Offset from which where text is visible on screen
  const visibleTextOffset = Math.round(firstTextNode.wholeText.length * (1 - getTextVisibleRatio(firstTextNode, viewport)));

  // Retrieving visible text
  fullVisibleText = Array.from(textNodes)
    .slice(0, 5)
    .map((text) => text.textContent)
    .join(" ")
    .substring(visibleTextOffset);

  // Offset to remove truncated words
  const cleanStartOffset = fullVisibleText.indexOf(" ") + 1;
  fullVisibleText = fullVisibleText.substring(cleanStartOffset, 250);

  // End offset to remove truncated words
  const cleanEndOffset = fullVisibleText.lastIndexOf(" ");

  // Return cleaned visible text
  return fullVisibleText.substring(0, cleanEndOffset);
}

function getFirstAndLastVisiblePartialCfis(viewport, elements) {
  let cfiElementFrom = null;
  const textNodes = elements.filter((el) => el.nodeType === Node.TEXT_NODE);
  if (textNodes.length > 0) {
    const firstTextNode = textNodes[0];
    const textOffset = Math.round(firstTextNode.wholeText.length * (1 - getTextVisibleRatio(firstTextNode, viewport)));
    cfiElementFrom = document.createRange();
    cfiElementFrom.setStart(firstTextNode, textOffset);
  } else {
    cfiElementFrom = elements[0];
  }

  let cfiElementTo = null;
  const lastTextNode = textNodes.pop();
  if (lastTextNode) {
    const textOffset = Math.round(lastTextNode.wholeText.length * getTextVisibleRatio(lastTextNode, viewport));
    cfiElementTo = document.createRange();
    cfiElementTo.setStart(lastTextNode, textOffset);
  } else {
    cfiElementTo = elements.pop();
  }

  return {
    startCfi: getPartialCfiFromElement(cfiElementFrom),
    endCfi: getPartialCfiFromElement(cfiElementTo),
  };
}

function getPartialCfiFromElement(element) {
  try {
    const epubCfi = new EpubCFI(element, "/6/2").toString();
    return epubCfi.substring(13, epubCfi.length - 1);
  } catch (error) {
    return null;
  }
}

/// User Settings.

export function setCSSProperties(properties) {
  for (const name in properties) {
    setProperty(name, properties[name]);
  }
}

// For setting user setting.
export function setProperty(key, value) {
  if (value === null || value === "") {
    removeProperty(key);
  } else {
    var root = document.documentElement;
    // The `!important` annotation is added with `setProperty()` because if it's part of the
    // `value`, it will be ignored by the Web View.
    root.style.setProperty(key, value, "important");
  }
}

// For removing user setting.
export function removeProperty(key) {
  var root = document.documentElement;

  root.style.removeProperty(key);
}

/// Toolkit

export function log() {
  var message = Array.prototype.slice.call(arguments).join(" ");
  Android.log(message);
}

export function logError(message) {
  Android.logError(message, "", 0);
}
