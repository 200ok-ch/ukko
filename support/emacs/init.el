;; No syntax highlighting from Emacs, because 200ok.ch uses
;; highlight.js
;; (require 'htmlize)
(setq org-html-htmlize-output-type nil)

(require 'org)

;; Don’t ask to execute a code block.
(setq org-confirm-babel-evaluate nil)
