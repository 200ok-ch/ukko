;; (defvar my-packages '(htmlize))

;; (dolist (p my-packages)
;;   (unless (package-installed-p p)
;;     (package-refresh-contents)
;;     (package-install p))
;;   (add-to-list 'package-selected-packages p))

;; (quelpa '(discover-my-major :fetcher git :url "https://github.com/hniksic/emacs-htmlize.git"))

;; (unless (package-installed-p 'quelpa)
;;   (with-temp-buffer
;;     (url-insert-file-contents "https://raw.githubusercontent.com/quelpa/quelpa/master/quelpa.el")
;;     (eval-buffer)
;;     (quelpa-self-upgrade)))

;; (add-to-list 'load-path (file-name-directory load-file-name))
;; (require 'htmlize)
;; ;; (load-library "tramp-gvfs")
;; ;; (setq tramp-gvfs-enabled t)
;; (require 'org)
;; (org-babel-do-load-languages
;;  'org-babel-load-languages
;;  '((shell . t)
;;    (emacs-lisp . t)))

;; ;; Don’t ask to execute a code block.
;; (setq org-confirm-babel-evaluate nil)
