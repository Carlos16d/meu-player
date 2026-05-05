// Registrar Service Worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', function() {
    navigator.serviceWorker.register('sw.js')
      .then(function() {
        console.log('Service Worker registrado!');
      })
      .catch(function(err) {
        console.log('Service Worker erro:', err);
      });
  });
}

// Instalação do PWA
let deferredPrompt;
const btnDownload = document.getElementById('btnDownload');

window.addEventListener('beforeinstallprompt', function(e) {
  e.preventDefault();
  deferredPrompt = e;
  btnDownload.style.display = 'block';
});

btnDownload.addEventListener('click', function() {
  if (deferredPrompt) {
    deferredPrompt.prompt();
    deferredPrompt.userChoice.then(function(result) {
      if (result.outcome === 'accepted') {
        btnDownload.textContent = '✅ Instalado!';
        setTimeout(function() {
          btnDownload.style.display = 'none';
        }, 2000);
      }
      deferredPrompt = null;
    });
  }
});

window.addEventListener('appinstalled', function() {
  btnDownload.textContent = '✅ Instalado!';
  setTimeout(function() {
    btnDownload.style.display = 'none';
  }, 2000);
});

// Animação dos quadrados
let passo = 0;
const area = document.getElementById('area');
const azul = document.getElementById('azul');

azul.addEventListener('click', function(e) {
  e.stopPropagation();
  if (passo < 3) {
    passo++;
    area.className = 'area p' + passo;
  } else {
    azul.classList.add('bater');
    setTimeout(function() {
      azul.classList.remove('bater');
    }, 300);
  }
});

// Previne scroll no mobile
document.body.addEventListener('touchmove', function(e) {
  e.preventDefault();
}, { passive: false });
