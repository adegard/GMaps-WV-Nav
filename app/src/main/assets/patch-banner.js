var head = document.getElementsByTagName('head');
if (head.length > 0) {
    var style = document.createElement('style');
    style.setAttribute('type', 'text/css');
    style.textContent = `.ml-persistent-promo-banner {
        display: none !important;
    }
    #app {
        top: 0 !important
    }`;
    head[0].appendChild(style);
}
