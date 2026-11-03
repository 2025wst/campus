/**
 * 注入到教务平台页面的抓取按钮。
 * 由 App 的原生窗口在每次页面加载完后注入；找不到时用 App 内置的兜底版本。
 *
 * 设计要点：
 * - 不依赖 alert（WebView 没配 WebChromeClient 时会静默丢弃）——所有反馈写在按钮文字上；
 * - 主文档和同源 iframe 都扫一遍（课表可能嵌在 iframe 里）；
 * - 出错也把错误写进按钮，方便截图反馈。
 */
(function () {
  if (window.__campusBtn) return;
  window.__campusBtn = true;

  function collect(doc, out) {
    try {
      var cells = doc.querySelectorAll('td.td-content');
      for (var i = 0; i < cells.length; i++) {
        var td = cells[i];
        var m = /td-content\s+(\d+)/.exec(td.className || '');
        if (!m) continue;
        var day = parseInt(m[1], 10);
        if (!(day >= 1 && day <= 7)) continue;
        var blocks = td.querySelectorAll('div.tdHtml');
        for (var j = 0; j < blocks.length; j++) {
          var blk = blocks[j];
          var nameEl = blk.querySelector('.course-name');
          if (!nameEl) continue;
          var name = (nameEl.textContent || '').trim();
          if (!name) continue;
          var info = '';
          var kids = blk.children;
          for (var k = 0; k < kids.length; k++) {
            var el = kids[k];
            var t = el.textContent || '';
            if (
              t.indexOf('周') >= 0 &&
              !el.classList.contains('course-name') &&
              !el.classList.contains('lesson-name')
            ) {
              info = t;
              break;
            }
          }
          if (!info) info = blk.textContent || '';
          info = info.replace(/\u00a0/g, ' ');
          if (info.indexOf('周') < 0) continue;
          out.push({ name: name, info: info, day: day });
        }
      }
    } catch (e) {
      /* 单个文档失败不影响其它 */
    }
  }

  var btn = document.createElement('div');
  btn.textContent = '导入到校园通';
  btn.style.cssText =
    'position:fixed;right:16px;bottom:110px;z-index:2147483647;background:#22c55e;color:#fff;' +
    'font-size:16px;font-weight:700;padding:14px 20px;border-radius:999px;' +
    'box-shadow:0 6px 20px rgba(0,0,0,.3);font-family:sans-serif;' +
    'touch-action:manipulation;-webkit-user-select:none;user-select:none;';

  var busy = false;
  btn.addEventListener('click', function (ev) {
    ev.preventDefault();
    ev.stopPropagation();
    if (busy) return;
    busy = true;
    btn.textContent = '读取中…';

    // 留一帧让文字先画出来，再做重活
    setTimeout(function () {
      var rows = [];
      collect(document, rows);

      if (!rows.length) {
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) collect(d, rows);
          } catch (e) {
            /* 跨域 iframe 跳过 */
          }
        }
      }

      if (!rows.length) {
        btn.textContent = '没找到课表（' + location.host + '），请到「我的课表」页再点';
        busy = false;
        return;
      }

      if (typeof CampusBridge === 'undefined') {
        btn.textContent = '桥接不可用（找到 ' + rows.length + ' 门）';
        busy = false;
        return;
      }

      btn.textContent = '找到 ' + rows.length + ' 门，导入中…';
      try {
        CampusBridge.emit(JSON.stringify(rows));
      } catch (e) {
        btn.textContent = '发送失败：' + e.message;
        busy = false;
      }
    }, 30);
  });

  (document.body || document.documentElement).appendChild(btn);
})();
