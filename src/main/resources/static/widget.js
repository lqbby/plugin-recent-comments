/*!
 * recent-comments widget v1.0.0
 * 全局最新评论侧边栏组件（plugin: recent-comments）。
 *
 * 用法（任意主题模板两行接入）：
 *   1. 在页面引入本脚本：
 *      <script src="/plugins/recent-comments/assets/widget.js" defer></script>
 *   2. 在需要的位置放置组件（侧边栏 / 页脚均可）：
 *      <recent-comments size="5"></recent-comments>
 *
 * 属性：
 *   size        条数，1-20，默认 5
 *   show-avatar 是否显示头像，默认开启；填 "false" 关闭
 *   show-time   是否显示相对时间，默认开启；填 "false" 关闭
 *   empty-text  无评论时的占位文案（默认「暂无评论」）
 *   error-text  拉取失败时的占位文案（默认不渲染并 console.warn）
 *
 * 样式：默认跟随宿主明暗（data-color-scheme 标记，其次 prefers-color-scheme），
 * 支持通过 CSS 变量覆写：--rc-text / --rc-muted / --rc-accent / --rc-bg / --rc-hover。
 */
(function () {
  'use strict';

  var ENDPOINT = '/apis/api.recent-comments.halo.run/v1alpha1/comments/latest';
  var WEAVATAR_BASE = 'https://weavatar.com/avatar/';
  var PALETTE = ['#1d9e75', '#378add', '#d4537e', '#d85a30', '#7f77dd', '#ba7517', '#639922', '#d8407f'];
  var MAX_CONTENT = 64;

  if (customElements.get('recent-comments')) {
    return;
  }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function stripHtml(value) {
    if (!value || value.indexOf('<') === -1) {
      return value || '';
    }
    var doc = new DOMParser().parseFromString(value, 'text/html');
    return (doc.body && doc.body.textContent) || '';
  }

  function truncate(text, max) {
    var clean = stripHtml(text).replace(/\s+/g, ' ').trim();
    return clean.length > max ? clean.slice(0, max) + '…' : clean;
  }

  function scheme() {
    var root = document.documentElement;
    var marker = root && root.dataset ? root.dataset.colorScheme : undefined;
    if (marker) {
      if (marker === 'auto' || marker === 'color-scheme-auto') {
        return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      }
      if (marker.indexOf('dark') !== -1) {
        return 'dark';
      }
      if (marker.indexOf('light') !== -1) {
        return 'light';
      }
    }
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  function relativeTime(iso, locale) {
    var then = new Date(iso);
    if (isNaN(then.getTime())) {
      return '';
    }
    var diffSec = Math.round((then.getTime() - Date.now()) / 1000);
    var abs = Math.abs(diffSec);
    if (abs < 3600) {
      return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(Math.round(diffSec / 60), 'minute');
    }
    if (abs < 86400 * 7) {
      return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(Math.round(diffSec / 3600), 'hour');
    }
    return then.toLocaleDateString(locale, { year: 'numeric', month: 'short', day: 'numeric' });
  }

  function avatarUrl(item) {
    if (item.avatar) {
      return item.avatar;
    }
    if (item.emailHash) {
      return WEAVATAR_BASE + item.emailHash + '?d=mp&f=webp&s=96';
    }
    return '';
  }

  function hashColor(text) {
    var hash = 0;
    var value = String(text || '?');
    for (var i = 0; i < value.length; i++) {
      hash = ((hash << 5) - hash + value.charCodeAt(i)) | 0;
    }
    return PALETTE[Math.abs(hash) % PALETTE.length];
  }

  var TEMPLATE = document.createElement('template');
  TEMPLATE.innerHTML =
    '<style>' +
    ':host{display:block;--rc-text:inherit;--rc-muted:inherit;--rc-accent:inherit;--rc-bg:transparent;--rc-hover:transparent;color:var(--rc-text,#222)}' +
    ':host([hidden]){display:none}' +
    '.rc-list{list-style:none;margin:0;padding:0;background:var(--rc-bg,transparent)}' +
    '.rc-item{display:flex;gap:10px;padding:10px 2px;border-bottom:1px solid color-mix(in srgb,currentColor 8%,transparent)}' +
    '.rc-item:last-child{border-bottom:0}' +
    '.rc-link{display:contents;text-decoration:none;color:inherit}' +
    '.rc-ava{flex:0 0 36px;width:36px;height:36px;border-radius:50%;overflow:hidden;background:#eee;color:#666;display:flex;align-items:center;justify-content:center;font-size:15px;font-weight:500;line-height:1}' +
    '.rc-ava img{width:100%;height:100%;object-fit:cover;display:block}' +
    '.rc-body{flex:1;min-width:0}' +
    '.rc-head{display:flex;align-items:baseline;gap:6px;flex-wrap:wrap}' +
    '.rc-name{font-weight:500;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}' +
    '.rc-time{font-size:11px;color:var(--rc-muted,#999);margin-left:auto;flex:0 0 auto}' +
    '.rc-content{font-size:13px;line-height:1.55;color:var(--rc-muted,#666);margin:2px 0 0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}' +
    '.rc-subject{font-size:11px;color:var(--rc-accent,#1d9e75);margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}' +
    '.rc-empty,.rc-error{padding:8px 2px;font-size:13px;color:var(--rc-muted,#999)}' +
    '</style>' +
    '<ul class="rc-list" part="list"></ul>';

  function toColorScheme(theme) {
    return theme === 'dark'
      ? { text: '#e5e7eb', muted: '#9ca3af', accent: '#5dcaa5', hover: 'rgba(255,255,255,.06)' }
      : { text: '#222', muted: '#6b7280', accent: '#0f6e56', hover: 'rgba(0,0,0,.05)' };
  }

  class RecentComments extends HTMLElement {
    static get observedAttributes() {
      return ['size', 'show-avatar', 'show-time', 'empty-text', 'error-text'];
    }

    constructor() {
      super();
      this._shadow = this.attachShadow({ mode: 'open' });
      this._shadow.appendChild(TEMPLATE.content.cloneNode(true));
      this._list = this._shadow.querySelector('.rc-list');
      this._list.addEventListener('error', function (e) {
        var img = e.target;
        if (img && img.tagName === 'IMG' && img.parentElement &&
            img.parentElement.classList.contains('rc-ava')) {
          img.parentElement.textContent = img.parentElement.dataset.letter || '?';
        }
      }, true);
    }

    connectedCallback() {
      this._applyTheme();
      this._load();
    }

    attributeChangedCallback() {
      if (this._shadow) {
        this._load();
      }
    }

    _applyTheme() {
      var colors = toColorScheme(scheme());
      this.style.setProperty('--rc-text', colors.text);
      this.style.setProperty('--rc-muted', colors.muted);
      this.style.setProperty('--rc-accent', colors.accent);
      this.style.setProperty('--rc-hover', colors.hover);
    }

    _attr(name, fallback) {
      var raw = this.getAttribute(name);
      return raw == null ? fallback : raw;
    }

    _size() {
      var parsed = parseInt(this.getAttribute('size') || '5', 10);
      if (isNaN(parsed)) {
        return 5;
      }
      return Math.max(1, Math.min(20, parsed));
    }

    _flag(name) {
      return this.getAttribute(name) !== 'false';
    }

    _load() {
      var self = this;
      var url = ENDPOINT + '?size=' + this._size();
      fetch(url, { headers: { Accept: 'application/json' }, credentials: 'same-origin' })
        .then(function (res) {
          if (!res.ok) {
            throw new Error('HTTP ' + res.status);
          }
          return res.json();
        })
        .then(function (payload) {
          var items = payload && Array.isArray(payload.items) ? payload.items : [];
          self._render(items);
        })
        .catch(function (err) {
          console.warn('[recent-comments] 拉取失败:', err);
          var text = self.getAttribute('error-text');
          if (text) {
            self._list.innerHTML = '<li class="rc-error">' + esc(text) + '</li>';
          }
        });
    }

    _render(items) {
      if (!items.length) {
        this._list.innerHTML = '<li class="rc-empty">' + esc(this._attr('empty-text', '暂无评论')) + '</li>';
        return;
      }
      var showAvatar = this._flag('show-avatar');
      var showTime = this._flag('show-time');
      var locale = navigator.language || 'zh-CN';
      var newTab = this._flag('open-new-tab');
      this._list.innerHTML = items.map(function (item) {
        var title = item.displayName || item.subjectTitle || '匿名';
        var url = avatarUrl(item);
        var letter = Array.from(title)[0] || '?';
        var avatar = '';
        if (showAvatar) {
          if (url) {
            avatar =
              '<span class="rc-ava" data-letter="' + esc(letter) + '" style="background:' + hashColor(title) + ';color:#fff">' +
              '<img src="' + esc(url) + '" alt="" loading="lazy" referrerpolicy="no-referrer"></span>';
          } else {
            avatar =
              '<span class="rc-ava" style="background:' + hashColor(title) + ';color:#fff">' +
              esc(letter) + '</span>';
          }
        }
        var content = truncate(item.content, MAX_CONTENT);
        var time = showTime && item.creationTime ? relativeTime(item.creationTime, locale) : '';
        var subject =
          item.subjectTitle && item.subjectTitle !== title
            ? '<div class="rc-subject">' + esc(item.subjectTitle) + '</div>'
            : '';
        var inner =
          avatar +
          '<div class="rc-body">' +
          '<div class="rc-head"><span class="rc-name">' + esc(title) + '</span>' +
          (time ? '<span class="rc-time">' + esc(time) + '</span>' : '') +
          '</div>' +
          (content ? '<p class="rc-content">' + esc(content) + '</p>' : '') +
          subject +
          '</div>';
        if (item.permalink) {
          return (
            '<li class="rc-item">' +
            '<a class="rc-link" href="' + esc(item.permalink) + '"' +
            (newTab ? ' target="_blank" rel="noopener"' : '') +
            '>' + inner + '</a></li>'
          );
        }
        return '<li class="rc-item">' + inner + '</li>';
      }).join('');
    }
  }

  customElements.define('recent-comments', RecentComments);
})();
