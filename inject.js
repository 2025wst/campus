/**
 * 注入到教务平台页面的抓取按钮（v4）。
 *
 * v4：实验课的一个格子里会重复出现多段（每周一段），按 .course-name 切分，
 *     把每一段都交回 App，由 App 合并成「一门课 + 多次安排」。
 */
(function () {
  var VERSION = 'v4';
  var existing = document.getElementById('campus-import-btn');
  if (existing) existing.parentNode.removeChild(existing);

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
          var cur = null;
          var kids = blk.children;
          for (var k = 0; k <= kids.length; k++) {
            var el = k < kids.length ? kids[k] : null;
            var isName = el && el.classList && el.classList.contains('course-name');
            if (isName || (el === null && cur)) {
              if (cur && cur.name && cur.info && cur.info.indexOf('周') >= 0) out.push(cur);
              cur = isName ? { name: (el.textContent || '').trim(), info: '', day: day } : null;
              continue;
            }
            if (!cur) continue;
            var t = el.textContent || '';
            if (!cur.info && t.indexOf('周') >= 0 && !(el.classList && el.classList.contains('lesson-name'))) {
              cur.info = t.replace(/\u00a0/g, ' ');
            }
          }
        }
      }
    } catch (e) {
      /* 单个文档失败不影响其它 */
    }
  }

  var btn = document.createElement('div');
  btn.id = 'campus-import-btn';
  btn.textContent = '导入到校园通·' + VERSION;
  btn.style.cssText =
    'position:fixed;right:16px;bottom:110px;z-index:2147483647;background:#22c55e;color:#fff;' +
    'font-size:16px;font-weight:700;padding:14px 20px;border-radius:999px;' +
    'box-shadow:0 6px 20px rgba(0,0,0,.35);font-family:sans-serif;' +
    'touch-action:manipulation;-webkit-user-select:none;user-select:none;' +
    'pointer-events:auto;opacity:1;transform:none;';

  var busy = false;

  function run(ev) {
    if (ev) {
      ev.preventDefault();
      ev.stopPropagation();
    }
    if (busy) return;
    busy = true;
    btn.textContent = '读取中…';

    setTimeout(function () {
      try {
        var rows = [];
        collect(document, rows);
        var where = 'top';

        if (!rows.length) {
          var frames = document.querySelectorAll('iframe');
          for (var i = 0; i < frames.length; i++) {
            try {
              var d = frames[i].contentDocument;
              if (d) {
                collect(d, rows);
                if (rows.length) {
                  where = 'iframe';
                  break;
                }
              }
            } catch (e) {
              /* 跨域 iframe 跳过 */
            }
          }
        }

        if (!rows.length) {
          btn.textContent = '没找到课表（' + location.host + ' / ' + VERSION + '）';
          busy = false;
          return;
        }
        if (typeof CampusBridge === 'undefined') {
          btn.textContent = '桥接不可用（' + rows.length + ' 段 / ' + where + '）';
          busy = false;
          return;
        }

        btn.textContent = '找到 ' + rows.length + ' 段，导入中…';
        CampusBridge.emit(JSON.stringify(rows));
      } catch (e) {
        btn.textContent = '出错：' + (e && e.message ? e.message : e);
        busy = false;
      }
    }, 30);
  }

  btn.addEventListener('pointerdown', run, false);
  btn.addEventListener('touchstart', run, false);
  btn.addEventListener('click', run, false);

  (document.body || document.documentElement).appendChild(btn);
})();
