'use strict';
(() => {
    const animations = new WeakMap();
    const maskedText = CSS.supports('-webkit-text-security', 'disc');
    let current, sequence = 0;
    function cancel(element) {
        const previous = animations.get(element);
        animations.delete(element);
        previous?.cancel();
    }
    function animate(element, frames, duration) {
        cancel(element);
        if (!element.animate || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return null;
        }
        const animation = element.animate(frames, { duration, easing: 'cubic-bezier(.2,.7,.2,1)' });
        animations.set(element, animation);
        animation.finished.catch(() => {});
        return animation;
    }
    function switchTransition(input) {
        const parts = control => [control.closest('.starbot-switch-row'), control, control.nextElementSibling];
        const properties = [['backgroundColor', 'borderColor', 'color'], ['backgroundColor', 'borderColor'], ['transform']];
        const styles = (element, index) => {
            const computed = getComputedStyle(element);
            return Object.fromEntries(properties[index].map(name => [name, computed[name]]));
        };
        const start = parts(input).map(styles);
        return next => parts(next).forEach((element, index) => animate(element, [start[index], styles(element, index)], 240));
    }
    function enter(element, kind = 'content') {
        const frames = kind === 'toast'
            ? [{ opacity: 0, transform: 'translate(-50%,-12px) scale(.98)' }, { opacity: 1, transform: 'translate(-50%,0) scale(1)' }]
            : kind === 'fade' ? [{ opacity: 0 }, { opacity: 1 }]
                : [{ opacity: 0, transform: kind === 'menu' ? 'translateY(-5px) scale(.98)' : 'translateY(7px)' }, { opacity: 1, transform: 'translateY(0) scale(1)' }];
        return animate(element, frames, kind === 'content' ? 560 : kind === 'menu' ? 280 : 340);
    }
    function leave(element, kind, complete) {
        const frames = kind === 'toast'
            ? [{ opacity: 1, transform: 'translate(-50%,0)' }, { opacity: 0, transform: 'translate(-50%,-8px)' }]
            : kind === 'fade' ? [{ opacity: getComputedStyle(element).opacity }, { opacity: 0 }]
                : [{ opacity: 1, transform: 'translateY(0) scale(1)' }, { opacity: 0, transform: 'translateY(-4px) scale(.98)' }];
        const animation = animate(element, frames, 240);
        if (!animation) {
            complete();
            return;
        }
        const finish = () => {
            if (animations.get(element) === animation) {
                animations.delete(element);
                complete();
            }
        };
        animation.finished.then(finish, finish);
    }
    function toggleNode(details) {
        const summary = details.querySelector(':scope > summary'), body = details.querySelector(':scope > .starbot-entry-body');
        if (!summary || !body) {
            return;
        }
        const expanded = details.dataset.expanded !== 'true';
        const startHeight = details.getBoundingClientRect().height;
        const startOpacity = details.open ? Number(getComputedStyle(body).opacity) : 0;
        cancel(details);
        cancel(body);
        details.dataset.expanded = String(expanded);
        details.open = true;
        details.style.overflow = 'hidden';
        body.inert = !expanded;
        const border = getComputedStyle(details), heading = getComputedStyle(summary);
        const collapsedHeight = summary.getBoundingClientRect().height - (parseFloat(heading.borderBottomWidth) || 0)
            + (parseFloat(border.borderTopWidth) || 0) + (parseFloat(border.borderBottomWidth) || 0);
        const endHeight = expanded ? details.getBoundingClientRect().height : collapsedHeight;
        const duration = expanded ? 400 : 340;
        const animation = animate(details, [{ height: startHeight + 'px' }, { height: endHeight + 'px' }], duration);
        animate(body, [{ opacity: startOpacity }, { opacity: expanded ? 1 : 0 }], duration);
        details.dispatchEvent(new Event('config-node-toggle', { bubbles: true }));
        const finish = () => {
            details.open = expanded;
            details.style.removeProperty('overflow');
            body.inert = false;
        };
        if (!animation) {
            finish();
            return;
        }
        const complete = () => {
            if (animations.get(details) === animation) {
                animations.delete(details);
                finish();
            }
        };
        animation.finished.then(complete, complete);
    }
    function secret(input, revealed = false) {
        input.dataset.revealed = String(revealed);
        input.type = maskedText || revealed ? 'text' : 'password';
        input.classList.toggle('starbot-secret-masked', maskedText && !revealed);
        input.autocomplete = 'off';
        input.spellcheck = false;
        input.setAttribute('autocapitalize', 'off');
        input.setAttribute('autocorrect', 'off');
        input.dataset.secretReady = 'true';
        const button = input.parentElement.querySelector('[data-action="reveal"]');
        if (button) {
            const label = (revealed ? '隐藏' : '显示') + (input.getAttribute('aria-label') || '密码');
            button.setAttribute('aria-pressed', String(revealed));
            button.setAttribute('aria-label', label);
            button.title = label;
        }
    }

    function updateTabs(nav) {
        const selected = nav.querySelector('[data-tab][aria-selected="true"]');
        if (!selected) {
            return;
        }
        let highlight = nav.querySelector('.starbot-tab-highlight');
        if (!highlight) {
            highlight = document.createElement('span');
            highlight.className = 'starbot-tab-highlight';
            highlight.setAttribute('aria-hidden', 'true');
            highlight.hidden = true;
            nav.prepend(highlight);
            const observer = new ResizeObserver(() => updateTabs(nav));
            observer.observe(nav);
            for (const tab of nav.querySelectorAll('[data-tab]')) {
                observer.observe(tab);
            }
        }
        if (!selected.offsetWidth) {
            return;
        }
        const ready = highlight.dataset.ready === 'true';
        if (!ready) {
            highlight.style.transition = 'none';
        }
        highlight.style.transform = 'translate(' + selected.offsetLeft + 'px,' + selected.offsetTop + 'px)';
        highlight.style.width = selected.offsetWidth + 'px';
        highlight.style.height = selected.offsetHeight + 'px';
        highlight.hidden = false;
        if (highlight.dataset.active !== selected.dataset.tab) {
            const left = selected.offsetLeft - 5, right = selected.offsetLeft + selected.offsetWidth + 5;
            if (left < nav.scrollLeft || right > nav.scrollLeft + nav.clientWidth) {
                nav.scrollTo({left: left < nav.scrollLeft ? left : right - nav.clientWidth,
                    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'});
            }
            highlight.dataset.active = selected.dataset.tab;
        }
        if (!ready) {
            highlight.getBoundingClientRect();
            highlight.style.transition = '';
            highlight.dataset.ready = 'true';
        }
    }
    function close(restoreFocus = false) {
        if (!current) {
            return;
        }
        const { trigger, menu } = current;
        current = undefined;
        trigger.setAttribute('aria-expanded', 'false');
        menu.inert = true;
        menu.setAttribute('aria-hidden', 'true');
        menu.removeAttribute('id');
        menu.style.pointerEvents = 'none';
        leave(menu, 'menu', () => menu.remove());
        if (restoreFocus && trigger.isConnected) {
            trigger.focus();
        }
    }
    function open(select, trigger, root, focusLast = false) {
        close();
        const menu = document.createElement('div');
        menu.className = 'starbot-select-menu';
        menu.id = trigger.getAttribute('aria-controls');
        menu.setAttribute('role', 'listbox');
        menu.setAttribute('aria-label', trigger.getAttribute('aria-label'));
        const options = Array.from(select.options);
        options.forEach(option => {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'starbot-select-option';
            item.setAttribute('role', 'option');
            item.setAttribute('aria-selected', String(option.selected));
            item.textContent = option.textContent;
            item.disabled = option.disabled;
            item.tabIndex = -1;
            item.addEventListener('click', () => {
                const key = select.dataset.selectKey;
                select.value = option.value;
                trigger.firstElementChild.textContent = option.textContent;
                close();
                select.dispatchEvent(new Event('change', { bubbles: true }));
                const replacement = Array.from(root.querySelectorAll('select')).find(candidate => candidate.dataset.selectKey === key);
                replacement?.parentElement.querySelector('.starbot-select-trigger')?.focus();
            });
            menu.appendChild(item);
        });
        root.appendChild(menu);
        const rect = trigger.getBoundingClientRect();
        const margin = 12, width = Math.min(Math.max(rect.width, 170), window.innerWidth - margin * 2);
        menu.style.width = width + 'px';
        menu.style.left = Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin)) + 'px';
        const below = window.innerHeight - rect.bottom - margin;
        const above = rect.top - margin;
        const upward = below < Math.min(menu.scrollHeight, 230) && above > below;
        menu.style.maxHeight = Math.max(80, Math.min(270, upward ? above - 6 : below - 6)) + 'px';
        menu.style.top = (upward ? Math.max(margin, rect.top - menu.offsetHeight - 6) : rect.bottom + 6) + 'px';
        menu.style.transformOrigin = upward ? 'bottom center' : 'top center';
        enter(menu, 'menu');
        trigger.setAttribute('aria-expanded', 'true');
        current = { select, trigger, menu };
        const enabled = Array.from(menu.querySelectorAll('button:not(:disabled)'));
        const selected = enabled.find(item => item.getAttribute('aria-selected') === 'true');
        (focusLast ? enabled.at(-1) : selected || enabled[0])?.focus();
        menu.addEventListener('keydown', event => {
            const index = enabled.indexOf(document.activeElement);
            let next;
            if (event.key === 'ArrowDown') {
                next = enabled[(index + 1) % enabled.length];
            }
            else if (event.key === 'ArrowUp') {
                next = enabled[(index - 1 + enabled.length) % enabled.length];
            }
            else if (event.key === 'Home') {
                next = enabled[0];
            }
            else if (event.key === 'End') {
                next = enabled.at(-1);
            }
            else if (event.key === 'Escape') {
                event.preventDefault();
                close(true);
                return;
            }
            else if (event.key === 'Tab') {
                close(true);
                return;
            }
            else if (event.key.length === 1 && event.key !== ' ') {
                const ordered = enabled.slice(index + 1).concat(enabled.slice(0, index + 1));
                next = ordered.find(item => item.textContent.toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()));
            }
            if (next) {
                event.preventDefault();
                next.focus();
                next.scrollIntoView({ block: 'nearest' });
            }
        });
    }
    function enhance(root) {
        for (const nav of root.querySelectorAll('.starbot-tabs')) {
            updateTabs(nav);
        }
        for (const details of root.querySelectorAll('.starbot-entry:not([data-node-ready])')) {
            details.dataset.nodeReady = 'true';
            details.dataset.expanded = String(details.open);
            details.querySelector(':scope > summary')?.addEventListener('click', event => {
                if (event.target.closest('button,input,select,textarea,a,label,[role="combobox"]')) {
                    return;
                }
                event.preventDefault();
                toggleNode(details);
            });
        }
        for (const input of root.querySelectorAll('[data-secret]:not([data-secret-ready])')) {
            secret(input);
        }
        if (current && !current.trigger.isConnected) {
            close();
        }
        for (const select of root.querySelectorAll('select:not([data-enhanced])')) {
            select.dataset.enhanced = 'true';
            select.dataset.selectKey = select.id || (select.dataset.edit ? 'edit:' + select.dataset.edit : select.dataset.treeType ? 'tree:' + select.dataset.treeType : 'select-' + ++sequence);
            const wrapper = document.createElement('div');
            wrapper.className = 'starbot-dropdown';
            const trigger = document.createElement('button');
            trigger.type = 'button';
            trigger.className = 'starbot-select-trigger';
            trigger.disabled = select.disabled;
            trigger.setAttribute('role', 'combobox');
            trigger.setAttribute('aria-haspopup', 'listbox');
            trigger.setAttribute('aria-expanded', 'false');
            trigger.setAttribute('aria-controls', 'starbot-options-' + ++sequence);
            const label = select.getAttribute('aria-label') || select.closest('label')?.firstChild?.textContent?.trim() || '值类型';
            trigger.setAttribute('aria-label', label);
            const text = document.createElement('span');
            text.textContent = select.selectedOptions[0]?.textContent || '请选择';
            trigger.appendChild(text);
            select.before(wrapper);
            wrapper.append(select, trigger);
            select.hidden = true;
            trigger.addEventListener('click', event => {
                event.preventDefault();
                event.stopPropagation();
                if (current?.trigger === trigger) {
                    close();
                }
                else {
                    open(select, trigger, root);
                }
            });
            trigger.addEventListener('keydown', event => {
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                    event.preventDefault();
                    open(select, trigger, root, event.key === 'ArrowUp');
                }
            });
        }
    }
    document.addEventListener('pointerdown', event => {
        if (current && !current.menu.contains(event.target) && !current.trigger.contains(event.target)) {
            close();
        }
    });
    document.addEventListener('focusin', event => {
        if (current && !current.menu.contains(event.target) && !current.trigger.contains(event.target)) {
            close();
        }
    });
    window.addEventListener('resize', () => close());
    document.addEventListener('scroll', event => {
        if (current && !current.menu.contains(event.target)) {
            close();
        }
    }, true);
    enhance(document);
    window.ConfigControls = { enhance, close, enter, leave, switchTransition, toggleSecret: input => secret(input, input.dataset.revealed !== 'true') };
})();
