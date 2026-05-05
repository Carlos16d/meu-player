// Registrar Service Worker
if ('serviceWorker' in navigator) {
  window.addEventListener('load', function() {
    navigator.serviceWorker.register('sw.js').then(function(registration) {
      console.log('Service Worker registrado com sucesso!');
    }).catch(function(err) {
      console.log('Service Worker falhou:', err);
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
  btnDownload.textContent = '📲 Instalar App';
});

btnDownload.addEventListener('click', function() {
  if (deferredPrompt) {
    deferredPrompt.prompt();
    deferredPrompt.userChoice.then(function(choiceResult) {
      if (choiceResult.outcome === 'accepted') {
        console.log('Usuário instalou o app!');
        btnDownload.textContent = '✅ Instalado!';
        setTimeout(function() {
          btnDownload.style.display = 'none';
        }, 2000);
      }
      deferredPrompt = null;
    });
  } else {
    alert('Para instalar:\n\nAndroid: Menu (⋮) > Instalar aplicativo\n\niPhone: Compartilhar > Adicionar à Tela de Início');
  }
});

window.addEventListener('appinstalled', function() {
  btnDownload.textContent = '✅ Instalado!';
  setTimeout(function() {
    btnDownload.style.display = 'none';
  }, 2000);
  deferredPrompt = null;
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