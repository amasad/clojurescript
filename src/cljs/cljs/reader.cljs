;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.reader
  (:require [goog.string :as gstring]
            [clojure.string :as string]
            [cljs.analyzer :as ana]))

(defprotocol PushbackReader
  (read-char [reader] "Returns the next char from the Reader,
nil if the end of stream has been reached")
  (unread [reader ch] "Push back a single character on to the stream"))

; Using two atoms is less idomatic, but saves the repeat overhead of map creation
(deftype StringPushbackReader [s index-atom buffer-atom]
  PushbackReader
  (read-char [reader]
             (if (empty? @buffer-atom)
               (let [idx @index-atom]
                 (swap! index-atom inc)
                 (aget s idx))
               (let [buf @buffer-atom]
                 (swap! buffer-atom rest)
                 (first buf))))
  (unread [reader ch] (swap! buffer-atom #(cons ch %))))

(defn push-back-reader [s]
  "Creates a StringPushbackReader from a given string"
  (StringPushbackReader. s (atom 0) (atom nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ^boolean whitespace?
  "Checks whether a given character is whitespace"
  [ch]
  (or (gstring/isBreakingWhitespace ch) (identical? \, ch)))

(defn- ^boolean numeric?
  "Checks whether a given character is numeric"
  [ch]
  (gstring/isNumeric ch))

(defn- ^boolean comment-prefix?
  "Checks whether the character begins a comment."
  [ch]
  (identical? \; ch))

(defn- ^boolean number-literal?
  "Checks whether the reader is at the start of a number literal"
  [reader initch]
  (or (numeric? initch)
      (and (or (identical? \+ initch) (identical? \- initch))
           (numeric? (let [next-ch (read-char reader)]
                       (unread reader next-ch)
                       next-ch)))))

(declare read macros dispatch-macros)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; read helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


; later will do e.g. line numbers...
(defn reader-error
  [rdr & msg]
  (throw (js/Error. (apply str msg))))

(defn ^boolean macro-terminating? [ch]
  (and (not (identical? ch "#"))
       (not (identical? ch \'))
       (not (identical? ch ":"))
       (macros ch)))

(defn read-token
  [rdr initch]
  (loop [sb (gstring/StringBuffer. initch)
         ch (read-char rdr)]
    (if (or (nil? ch)
            (whitespace? ch)
            (macro-terminating? ch))
      (do (unread rdr ch) (. sb (toString)))
      (recur (do (.append sb ch) sb) (read-char rdr)))))

(defn read-line
  "Reads to the end of a line and returns the line."
  [rdr]
  (loop [sb (gstring/StringBuffer.)
         ch (read-char rdr)]
    (cond
      (and (nil? ch) (= 0 (.getLength sb)))
      nil

      (or (identical? ch "\n") (identical? ch "\r") (nil? ch))
      (. sb (toString))

      :else
      (recur (do (.append sb ch) sb) (read-char rdr)))))

(defn line-seq [rdr]
  "Returns the lines of text from rdr as a lazy sequence of strings."
  (when-let [line (read-line rdr)]
    (cons line (lazy-seq (line-seq rdr)))))

(defn skip-line
  "Advances the reader to the end of a line. Returns the reader"
  [reader _]
  (loop []
    (let [ch (read-char reader)]
      (if (or (identical? ch "\n") (identical? ch "\r") (nil? ch))
        reader
        (recur)))))

;; Note: Input begin and end matchers are used in a pattern since otherwise
;; anything begininng with `0` will match just `0` cause it's listed first.
(def int-pattern (re-pattern "^([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?$"))
(def ratio-pattern (re-pattern "([-+]?[0-9]+)/([0-9]+)"))
(def float-pattern (re-pattern "([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?"))

(defn- re-find*
  [re s]
  (let [matches (.exec re s)]
    (when-not (nil? matches)
      (if (== (alength matches) 1)
        (aget matches 0)
        matches))))

(defn- match-int
  [s]
  (let [groups (re-find* int-pattern s)
        group3 (aget groups 2)]
    (if-not (or (nil? group3)
                (< (alength group3) 1))
      0
      (let [negate (if (identical? "-" (aget groups 1)) -1 1)
            a (cond
               (aget groups 3) (array (aget groups 3) 10)
               (aget groups 4) (array (aget groups 4) 16)
               (aget groups 5) (array (aget groups 5) 8)
               (aget groups 7) (array (aget groups 7) (js/parseInt (aget groups 7)))
               :default (array nil nil))
            n (aget a 0)
            radix (aget a 1)]
        (if (nil? n)
          nil
          (* negate (js/parseInt n radix)))))))


(defn- match-ratio
  [s]
  (let [groups (re-find* ratio-pattern s)
        numinator (aget groups 1)
        denominator (aget groups 2)]
    (/ (js/parseInt numinator) (js/parseInt denominator))))

(defn- match-float
  [s]
  (js/parseFloat s))

(defn- re-matches*
  [re s]
  (let [matches (.exec re s)]
    (when (and (not (nil? matches))
               (identical? (aget matches 0) s))
      (if (== (alength matches) 1)
        (aget matches 0)
        matches))))

(defn- match-number
  [s]
  (cond
   (re-matches* int-pattern s) (match-int s)
   (re-matches* ratio-pattern s) (match-ratio s)
   (re-matches* float-pattern s) (match-float s)))

(defn escape-char-map [c]
  (cond
   (identical? c \t) "\t"
   (identical? c \r) "\r"
   (identical? c \n) "\n"
   (identical? c \\) \\
   (identical? c \") \"
   (identical? c \b) "\b"
   (identical? c \f) "\f"
   :else nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; unicode
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-2-chars [reader]
  (.toString
    (gstring/StringBuffer.
      (read-char reader)
      (read-char reader))))

(defn read-4-chars [reader]
  (.toString
    (gstring/StringBuffer.
      (read-char reader)
      (read-char reader)
      (read-char reader)
      (read-char reader))))

(def unicode-2-pattern (re-pattern "[0-9A-Fa-f]{2}"))
(def unicode-4-pattern (re-pattern "[0-9A-Fa-f]{4}"))

(defn validate-unicode-escape [unicode-pattern reader escape-char unicode-str]
  (if (re-matches unicode-pattern unicode-str)
    unicode-str
    (reader-error reader "Unexpected unicode escape \\" escape-char unicode-str)))

(defn make-unicode-char [code-str]
    (let [code (js/parseInt code-str 16)]
      (.fromCharCode js/String code)))

(defn escape-char
  [buffer reader]
  (let [ch (read-char reader)
        mapresult (escape-char-map ch)]
    (if mapresult
      mapresult
      (cond
        (identical? ch \x)
        (->> (read-2-chars reader)
          (validate-unicode-escape unicode-2-pattern reader ch)
          (make-unicode-char))

        (identical? ch \u)
        (->> (read-4-chars reader)
          (validate-unicode-escape unicode-4-pattern reader ch)
          (make-unicode-char))

        (numeric? ch)
        (.fromCharCode js/String ch)

        :else
        (reader-error reader "Unexpected unicode escape \\" ch )))))

(defn read-past
  "Read until first character that doesn't match pred, returning
   char."
  [pred rdr]
  (loop [ch (read-char rdr)]
    (if (pred ch)
      (recur (read-char rdr))
      ch)))

(defn read-delimited-list
  [delim rdr recursive?]
  (loop [a (transient [])]
    (let [ch (read-past whitespace? rdr)]
      (when-not ch (reader-error rdr "EOF while reading"))
      (if (identical? delim ch)
        (persistent! a)
        (if-let [macrofn (macros ch)]
          (let [mret (macrofn rdr ch)]
            (recur (if (identical? mret rdr) a (conj! a mret))))
          (do
            (unread rdr ch)
            (let [o (read rdr true nil recursive?)]
              (recur (if (identical? o rdr) a (conj! a o))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; data structure readers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn not-implemented
  [rdr ch]
  (reader-error rdr "Reader for " ch " not implemented yet"))

(declare maybe-read-tagged-type)

(defn read-dispatch
  [rdr _]
  (let [ch (read-char rdr)
        dm (dispatch-macros ch)]
    (if dm
      (dm rdr _)
      (if-let [obj (maybe-read-tagged-type rdr ch)]
        obj
        (reader-error rdr "No dispatch macro for " ch)))))

(defn read-unmatched-delimiter
  [rdr ch]
  (reader-error rdr "Unmatched delimiter " ch))

(defn read-list
  [rdr _]
  (apply list (read-delimited-list ")" rdr true)))

(def read-comment skip-line)

(defn read-vector
  [rdr _]
  (read-delimited-list "]" rdr true))

(defn read-map
  [rdr _]
  (let [l (read-delimited-list "}" rdr true)]
    (when (odd? (count l))
      (reader-error rdr "Map literal must contain an even number of forms"))
    (apply hash-map l)))

(defn read-number
  [reader initch]
  (loop [buffer (gstring/StringBuffer. initch)
         ch (read-char reader)]
    (if (or (nil? ch) (whitespace? ch) (macros ch))
      (do
        (unread reader ch)
        (let [s (. buffer (toString))]
          (or (match-number s)
              (reader-error reader "Invalid number format [" s "]"))))
      (recur (do (.append buffer ch) buffer) (read-char reader)))))

(defn read-string*
  [reader _]
  (loop [buffer (gstring/StringBuffer.)
         ch (read-char reader)]
    (cond
     (nil? ch) (reader-error reader "EOF while reading")
     (identical? "\\" ch) (recur (do (.append buffer (escape-char buffer reader)) buffer)
                        (read-char reader))
     (identical? \" ch) (. buffer (toString))
     :default (recur (do (.append buffer ch) buffer) (read-char reader)))))

(defn special-symbols [t not-found]
  (cond
   (identical? t "nil") nil
   (identical? t "true") true
   (identical? t "false") false
   :else not-found))

(defn read-symbol
  [reader initch]
  (let [token (read-token reader initch)]
    (if (gstring/contains token "/")
      (symbol (subs token 0 (.indexOf token "/"))
              (subs token (inc (.indexOf token "/")) (.-length token)))
      (special-symbols token (symbol token)))))

;; based on matchSymol in clojure/lang/LispReader.java
(defn read-keyword
  [reader initch]
  (let [token (read-token reader (read-char reader))
        parts (string/split token #"/")
        name (last parts)
        ns (if (> (count parts) 1) (string/join \/ (butlast parts)))
        issue (cond
               (identical? (last ns) \:) "namespace can't ends with \":\""
               (identical? (last name) \:) "name can't end with \":\""
               (identical? (last name) \/) "name can't end with \"/\""
               (> (count (string/split token #"::")) 1) "name can't contain \"::\"")]
    (if issue
      (reader-error reader "Invalid token (" issue "): " token)
      (if (and (not ns) (identical? (first name) \:))
        (keyword *ns-sym* (apply str (rest name)))    ;; namespaced keyword using default
        (keyword ns name)))))

(defn desugar-meta
  [f]
  (cond
   (symbol? f) {:tag f}
   (string? f) {:tag f}
   (keyword? f) {f true}
   :else f))

(defn wrapping-reader
  [sym]
  (fn [rdr _]
    (list sym (read rdr true nil true))))

(defn throwing-reader
  [msg]
  (fn [rdr _]
    (reader-error rdr msg)))

(defn read-meta
  [rdr _]
  (let [m (desugar-meta (read rdr true nil true))]
    (when-not (map? m)
      (reader-error rdr "Metadata must be Symbol,Keyword,String or Map"))
    (let [o (read rdr true nil true)]
      (if (satisfies? IWithMeta o)
        (with-meta o (merge (meta o) m))
        (reader-error rdr "Metadata can only be applied to IWithMetas")))))

(def UNQUOTE :__thisInternalKeywordRepresentsUnquoteToTheReader__)
(def UNQUOTE-SPLICING :__thisInternalKeywordRepresentsUnquoteSplicingToTheReader__)

(declare syntaxQuote)
(def ^:dynamic *gensym-env* (atom nil))
(def ^:dynamic *arg-env* (atom nil))

(defn isUnquote? [form]
  (and (satisfies? ISeq form) (= (first form) UNQUOTE)))

(defn isUnquoteSplicing? [form]
  (and (satisfies? ISeq form) (= (first form) UNQUOTE-SPLICING)))

(defn sqExpandList [sq]
  (doall
    (for [item sq]
      (cond
        (isUnquote? item)
        (list 'list (second item))
  
        (isUnquoteSplicing? item)
        (second item)
  
        :else
        (list 'list (syntaxQuote item))))))

(defn syntaxQuote [form]
  (cond
    ;; (Compiler.isSpecial(form))
    (get ana/specials form)
    (list 'quote form)

    ;; (form instanceof Symbol)
    (symbol? form)
    (let [sym form
          name (name sym)
          ns (namespace sym)
          var (ana/resolve-existing-var (ana/empty-env) sym)]
      (cond
        ;; no namespace and name ends with #
        (and (not ns) (= "#" (last name)))
        (let [new-name (subs name 0 (- (count name) 1))
              gmap @*gensym-env*]
          (when (not gmap)
            (reader-error nil "Gensym literal not in syntax-quote"))
          (let [gs (or (get gmap sym)
                       (gensym (str new-name "__auto__")))]
            (swap! *gensym-env* assoc sym gs)
            (list 'quote gs)))
  
        ;; no namespace and name ends with .
        (and (not ns) (= "." (last name)))
        (let [new-name (subs name 0 (- (count name) 1))
              new-var (ana/resolve-existing-var
                        (ana/empty-env) (symbol new-name))]
          (list 'quote (:name new-var)))
  
        ;; no namespace and name begins with .
        (and (not ns) (= "." (first name)))
        (list 'quote sym)
        
        ;; resolve symbol
        :else
        (list 'quote
          (:name
            (cljs.analyzer/resolve-existing-var (cljs.analyzer/empty-env) sym)))))
    
    ;; (isUnquote(form))
    (isUnquote? form)
    (second form)

    ;; (isUnquoteSplicing(form))
    (isUnquoteSplicing? form)
    (reader-error rdr "Reader ~@ splice not in list")

    ;; TODO: figure out why nil is mapping to IMap
    (nil? form)
    (list 'quote form)

    ;; (form instanceof IPersistentCollection)
    (satisfies? ICollection form)
    (cond
      (satisfies? IRecord form)
      form

      (satisfies? IMap form)
      (list 'apply 'hash-map (list 'seq (cons 'concat (sqExpandList (apply concat (seq form))))))

      (satisfies? IVector form)
      (list 'apply 'vector (list 'seq (cons 'concat (sqExpandList form))))

      (satisfies? ISet form)
      (list 'apply 'hash-set (list 'seq (cons 'concat (sqExpandList (seq form)))))

      (or (satisfies? ISeq form) (satisfies? IList form))
      (if-let [sq (seq form)]
        (list 'seq (cons 'concat (sqExpandList sq)))
        (cons 'list nil))

      :else
      (reader-error rdr "Unknown Collection type"))

    ;; (form instanceof Keyword || form instanceof Number ||
    ;;  form instanceof Character || form instanceof String)
    (or (keyword? form) (number? form) (string? form))
    form

    :else
    (list 'quote form)
    ))

(defn read-syntax-quote
  [rdr _]
  (binding [*gensym-env* (atom {})]
    (let [form (read rdr true nil true)]
      (syntaxQuote form))))

(defn read-unquote
  [rdr _]
  (let [ch (read-char rdr)]
    (cond
      (= nil ch)
      (reader-error rdr "EOF while reading character")

      (= "@" ch)
      (let [o (read rdr true nil true)]
        (list UNQUOTE-SPLICING o))

      :else
      (do
        (unread rdr ch)
        (let [o (read rdr true nil true)]
          (list UNQUOTE o))))))

(defn garg [n]
  (let [pre (if (= n -1) "rest" (str "p" n))]
    (symbol (str (gensym pre) "#"))))

(defn read-fn
  [rdr _]
  (when @*arg-env*
    (reader-error nil "nested #()s are not allowed"))
  (binding [*arg-env* (atom (sorted-map))]
    (unread rdr "(")  ;) - the wink towards vim paren matching
    (let [form (read rdr true nil true)
          argsyms @*arg-env*
          rargs (rseq argsyms)
          highpair (first rargs)
          higharg (if highpair (key highpair) 0)
          args (if (> higharg 0)
                 (doall (for [i (range 1 (+ 1 higharg))]
                          (or (get argsyms i)
                              (garg i))))
                 args)
          restsym (get argsyms -1)
          args (if restsym
                 (concat args ['& restsym])
                 args)]
      ;(println "here1" (list 'fn* (vec args) form))
      (list 'fn* (vec args) form))))

(defn registerArg [n]
  (let [argsyms @*arg-env*]
    (when-not argsyms (reader-error _ "arg literal not in #()"))
    (let [ret (get argsyms n)]
      (if ret
        ret
        (let [ret (garg n)]
          (swap! *arg-env* assoc n ret)
          ret)))))

(defn read-arg
  [rdr pct]
  (if (not @*arg-env*)
    (read-symbol rdr "%")
    (let [ch (read-char rdr)]
      (unread rdr ch)
      ;; % alone is first arg
      (if (or (nil? ch)
                (whitespace? ch)
                (macro-terminating? ch))
        (registerArg 1)
        (let [n (read rdr true nil true)]
          (cond
            (= '& n) 
            (registerArg -1)
  
            (not (number? n))
            (reader-error rdr "arg literal must be %, %& or %integer")
  
            :else
            (registerArg (int n))))))))

(defn read-set
  [rdr _]
  (set (read-delimited-list "}" rdr true)))

(defn read-regex
  [reader]
  (loop [buffer ""
         ch (read-char reader)]

    (cond
     (nil? ch)
      (reader-error reader "EOF while reading regex")
     (identical? \\ ch)
      (recur (str buffer ch (read-char reader))
             (read-char reader))
     (identical? "\"" ch)
      (re-pattern buffer)
     :default
      (recur (str buffer ch) (read-char reader)))))

(defn read-discard
  [rdr _]
  (read rdr true nil true)
  rdr)

(defn macros [c]
  (cond
   (identical? c \") read-string*
   (identical? c \:) read-keyword
   (identical? c \;) read-comment
   (identical? c \') (wrapping-reader 'quote)
   (identical? c \@) (wrapping-reader 'deref)
   (identical? c \^) read-meta
   (identical? c \`) read-syntax-quote
   (identical? c \~) read-unquote
   (identical? c \() read-list
   (identical? c \)) read-unmatched-delimiter
   (identical? c \[) read-vector
   (identical? c \]) read-unmatched-delimiter
   (identical? c \{) read-map
   (identical? c \}) read-unmatched-delimiter
   (identical? c \\) read-char
   (identical? c \%) read-arg
   (identical? c \#) read-dispatch
   :else nil))

;; omitted by design: var reader, eval reader
(defn dispatch-macros [s]
  (cond
   (identical? s "{") read-set
   (identical? s "(") read-fn
   (identical? s "<") (throwing-reader "Unreadable form")
   (identical? s "\"") read-regex
   (identical? s"!") read-comment
   (identical? s "_") read-discard
   :else nil))

(defn read
  "Reads the first object from a PushbackReader. Returns the object read.
   If EOF, throws if eof-is-error is true. Otherwise returns sentinel."
  ([reader]
    (read reader true nil))
  ([reader eof-is-error sentinel]
    (read reader eof-is-error sentinel false))
  ([reader eof-is-error sentinel is-recursive]
    (let [ch (read-char reader)]
      (cond
       (nil? ch) (if eof-is-error (reader-error reader "EOF while reading") sentinel)
       (whitespace? ch) (recur reader eof-is-error sentinel is-recursive)
       (comment-prefix? ch) (recur (read-comment reader ch) eof-is-error sentinel is-recursive)
       :else (let [f (macros ch)
                   res
                   (cond
                    f (f reader ch)
                    (number-literal? reader ch) (read-number reader ch)
                    :else (read-symbol reader ch))]
       (if (identical? res reader)
         (recur reader eof-is-error sentinel is-recursive)
         res))))))

(defn read-string
  "Reads one object from the string s"
  [s]
  (let [r (push-back-reader s)]
    (read r true nil false)))


;; read instances

(defn ^:private zero-fill-right [s width]
  (cond (= width (count s)) s
        (< width (count s)) (.substring s 0 width)
        :else (loop [b (gstring/StringBuffer. s)]
                (if (< (.getLength b) width)
                  (recur (.append b \0))
                  (.toString b)))))

(defn ^:private divisible?
  [num div]
  (zero? (mod num div)))

(defn ^:private indivisible?
  [num div]
    (not (divisible? num div)))

(defn ^:private leap-year?
  [year]
  (and (divisible? year 4)
       (or (indivisible? year 100)
           (divisible? year 400))))

(def ^:private days-in-month
  (let [dim-norm [nil 31 28 31 30 31 30 31 31 30 31 30 31]
        dim-leap [nil 31 29 31 30 31 30 31 31 30 31 30 31]]
    (fn [month leap-year?]
      (get (if leap-year? dim-leap dim-norm) month))))

(def ^:private parse-and-validate-timestamp
  (let [timestamp #"(\d\d\d\d)(?:-(\d\d)(?:-(\d\d)(?:[T](\d\d)(?::(\d\d)(?::(\d\d)(?:[.](\d+))?)?)?)?)?)?(?:[Z]|([-+])(\d\d):(\d\d))?"
        check (fn [low n high msg]
                (assert (<= low n high) (str msg " Failed:  " low "<=" n "<=" high))
                n)]
    (fn [ts]
      (when-let [[[_ years months days hours minutes seconds milliseconds] [_ _ _] :as V]
                 (->> ts
                      (re-matches timestamp)
                      (split-at 8)
                      (map vec))]
        (let [[[_ y mo d h m s ms] [offset-sign offset-hours offset-minutes]]
              (->> V
                   (map #(update-in %2 [0] %)
                        [(constantly nil) #(if (= % "-") "-1" "1")])
                   (map (fn [v] (map #(js/parseInt % 10) v))))
              offset (* offset-sign (+ (* offset-hours 60) offset-minutes))]
          [(if-not years 1970 y)
           (if-not months 1        (check 1 mo 12 "timestamp month field must be in range 1..12"))
           (if-not days 1          (check 1 d (days-in-month mo (leap-year? y)) "timestamp day field must be in range 1..last day in month"))
           (if-not hours 0         (check 0 h 23 "timestamp hour field must be in range 0..23"))
           (if-not minutes 0       (check 0 m 59 "timestamp minute field must be in range 0..59"))
           (if-not seconds 0       (check 0 s (if (= m 59) 60 59) "timestamp second field must be in range 0..60"))
           (if-not milliseconds 0  (check 0 ms 999 "timestamp millisecond field must be in range 0..999"))
           offset])))))

(defn parse-timestamp
  [ts]
  (if-let [[years months days hours minutes seconds ms offset]
           (parse-and-validate-timestamp ts)]
    (js/Date.
     (- (.UTC js/Date years (dec months) days hours minutes seconds ms)
        (* offset 60 1000)))
    (reader-error nil (str "Unrecognized date/time syntax: " ts))))

(defn ^:private read-date
  [s]
  (if (string? s)
    (parse-timestamp s)
    (reader-error nil "Instance literal expects a string for its timestamp.")))


(defn ^:private read-queue
  [elems]
  (if (vector? elems)
    (into cljs.core.PersistentQueue/EMPTY elems)
    (reader-error nil "Queue literal expects a vector for its elements.")))


(defn ^:private read-uuid
  [uuid]
  (if (string? uuid)
    (UUID. uuid)
    (reader-error nil "UUID literal expects a string as its representation.")))

(def *tag-table* (atom {"inst"  read-date
                        "uuid"  read-uuid
                        "queue" read-queue}))

(defn maybe-read-tagged-type
  [rdr initch]
  (let [tag  (read-symbol rdr initch)]
    (if-let [pfn (get @*tag-table* (name tag))]
      (pfn (read rdr true nil false))
      (reader-error rdr
                    "Could not find tag parser for " (name tag)
                    " in " (pr-str (keys @*tag-table*))))))

(defn register-tag-parser!
  [tag f]
  (let [tag (name tag)
        old-parser (get @*tag-table* tag)]
    (swap! *tag-table* assoc tag f)
    old-parser))

(defn deregister-tag-parser!
  [tag]
  (let [tag (name tag)
        old-parser (get @*tag-table* tag)]
    (swap! *tag-table* dissoc tag)
    old-parser))
