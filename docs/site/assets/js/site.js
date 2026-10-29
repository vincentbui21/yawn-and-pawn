/*
 * Yawn & Pawn site motion. Everything here is optional: without JavaScript, or when the reader asks for reduced
 * motion, every element shows its final state. This file only adds classes and CSS variables, and makes confetti.
 */
(function () {
  "use strict";

  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)");
  if (!("IntersectionObserver" in window) || (reduce && reduce.matches)) return;

  var root = document.documentElement;
  var COLORS = ["#F5A04E", "#D96F14", "#A8520A", "#0F5534", "#FFD9A8"];

  /* Confetti: a short burst of brand-coloured pieces from the centre of an element. */
  function confetti(el, count) {
    var rect = el.getBoundingClientRect();
    var box = document.createElement("span");
    box.className = "confetti";
    box.setAttribute("aria-hidden", "true");
    box.style.left = rect.left + rect.width / 2 + "px";
    box.style.top = rect.top + rect.height / 2 + "px";
    var done = 0;
    for (var n = 0; n < count; n++) {
      var piece = document.createElement("i");
      piece.style.background = COLORS[n % COLORS.length];
      if (n % 3 === 0) piece.style.borderRadius = "50%";
      box.appendChild(piece);
      var angle = Math.random() * Math.PI * 2;
      var dist = 50 + Math.random() * 70;
      var x = Math.cos(angle) * dist;
      var y = Math.sin(angle) * dist * 0.7 - 30;
      var spin = (Math.random() - 0.5) * 540;
      var anim = piece.animate(
        [
          { transform: "translate(0,0) rotate(0) scale(0.6)", opacity: 1 },
          { transform: "translate(" + x + "px," + y + "px) rotate(" + spin / 2 + "deg) scale(1)", opacity: 1, offset: 0.55 },
          { transform: "translate(" + x * 1.15 + "px," + (y + 60) + "px) rotate(" + spin + "deg) scale(0.9)", opacity: 0 }
        ],
        { duration: 900 + Math.random() * 400, easing: "cubic-bezier(.2,.7,.3,1)", fill: "forwards" }
      );
      anim.onfinish = function () {
        if (++done === count) box.remove();
      };
    }
    document.body.appendChild(box);
  }

  /* The ladder: prices count up as their bars grow, then "I'm up · Free" pops with confetti. */
  function playLadder(ladder) {
    var prices = ladder.querySelectorAll("[data-count]");
    Array.prototype.forEach.call(prices, function (el, i) {
      var target = parseInt(el.getAttribute("data-count"), 10);
      var start = performance.now() + 300 + i * 180;
      el.textContent = "$0";
      function tick(now) {
        var t = Math.min(1, Math.max(0, (now - start) / 700));
        el.textContent = "$" + Math.round(t * target);
        if (t < 1) requestAnimationFrame(tick);
      }
      requestAnimationFrame(tick);
    });
    var free = ladder.querySelector(".ladder-up-price");
    if (free) setTimeout(function () { confetti(free, 22); }, 1750);
  }

  /* Scroll reveals, staggered among siblings. */
  var items = document.querySelectorAll("[data-reveal]");
  Array.prototype.forEach.call(items, function (el) {
    var siblings = Array.prototype.filter.call(el.parentNode.children, function (c) {
      return c.hasAttribute("data-reveal");
    });
    el.style.setProperty("--ri", String(Math.min(siblings.indexOf(el), 6)));
  });
  var observer = new IntersectionObserver(
    function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        el.classList.add("is-in");
        observer.unobserve(el);
        if (el.hasAttribute("data-ladder")) playLadder(el);
      });
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.12 }
  );
  root.classList.add("reveal-ready");
  Array.prototype.forEach.call(items, function (el) { observer.observe(el); });

  /* Screenshot phones: a slight tilt and drift as they cross the screen. */
  var shots = document.querySelectorAll("[data-parallax]");
  if (shots.length) {
    var queued = false;
    var update = function () {
      queued = false;
      var mid = window.innerHeight / 2;
      Array.prototype.forEach.call(shots, function (el) {
        var r = el.getBoundingClientRect();
        var p = (r.top + r.height / 2 - mid) / (window.innerHeight / 2 + r.height / 2);
        el.style.setProperty("--p", Math.max(-1, Math.min(1, p)).toFixed(3));
      });
    };
    var onScroll = function () {
      if (!queued) { queued = true; requestAnimationFrame(update); }
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    update();
  }

  /* Questions: let an answer fade out before its question closes. */
  Array.prototype.forEach.call(document.querySelectorAll(".faq-item"), function (item) {
    var summary = item.querySelector("summary");
    summary.addEventListener("click", function (event) {
      if (!item.open || item.classList.contains("is-closing")) return;
      event.preventDefault();
      item.classList.add("is-closing");
      setTimeout(function () {
        item.open = false;
        item.classList.remove("is-closing");
      }, 200);
    });
  });

  /* The test button: a small burst when it is pointed at or focused, at most every two seconds. */
  Array.prototype.forEach.call(document.querySelectorAll("[data-confetti-hover]"), function (btn) {
    var last = 0;
    var burst = function () {
      var now = Date.now();
      if (now - last < 2000) return;
      last = now;
      confetti(btn, 18);
    };
    btn.addEventListener("mouseenter", burst);
    btn.addEventListener("focus", burst);
  });
})();
