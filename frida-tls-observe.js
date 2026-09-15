'use strict';

// Observation-only hooks for an app you own or are authorized to test.
// Logs API metadata and bounded payload sizes; it does not disable pinning.
Java.perform(function () {
  try {
    const SSL_write = Module.findExportByName(null, 'SSL_write');
    const SSL_read = Module.findExportByName(null, 'SSL_read');
    [
      ['SSL_write', SSL_write],
      ['SSL_read', SSL_read],
    ].forEach(function ([name, addr]) {
      if (!addr) return;
      Interceptor.attach(addr, {
        onEnter(args) {
          this.len = args[2].toInt32();
          send({type: name, direction: name === 'SSL_write' ? 'out' : 'in', length: this.len});
        }
      });
    });
  } catch (e) {
    send({type: 'error', message: String(e)});
  }
});
