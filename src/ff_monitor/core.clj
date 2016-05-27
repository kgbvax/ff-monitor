(ns ff-monitor.core
  (:gen-class)
  (:require [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [clostache.parser :as c]
            [clojure.java.io :refer [as-url]]
            [clojure.data.json :as json]
            [postal.core :as postal]
            [postal.message :as message]
            [cprop.core :refer [load-config]]
            [clojure.spec :as spec]
            [clojure.tools.logging :as log])
  (:import (java.lang Exception)))

;; if DEBUG true, all notification emails will be sent to a
;; test email address instead of real owners' email addresses
(def DEBUG false)

(defn contains-valid-email-address?
"Checks if given string is a valid email address (RFC 2822 compliant)."
  [^String email-address]
  (let [address (message/make-address email-address "utf-8")]
    (not (nil? address))))

;; config spec
(spec/def ::truthy (spec/nilable #(instance? Boolean %)))
(spec/def ::url #(some? (try (as-url %) (catch Exception e))))

(spec/def ::nodes-urls (spec/* ::url))
(spec/def ::ssl #(instance? Boolean %))
(spec/def ::host string?)
(spec/def ::user string?)
(spec/def ::smtp (spec/keys :req-un [::host ::user] :opt-un [::pass ::ssl]))
(spec/def ::from contains-valid-email-address?)
(spec/def ::email (spec/keys :req-un [::smtp ::from]))
(spec/def ::config (spec/keys :req-un [::nodes-urls ::email]))

;; access paths into node status info maps
(spec/def ::contact contains-valid-email-address?)
(spec/def ::send_alerts ::truthy)
(spec/def ::hostname string?)
(spec/def ::node_id some?)
(spec/def ::online ::truthy)

(def email-address-path [:nodeinfo :owner :contact])
(def send-alerts?-path [:nodeinfo :send_alerts])
(def hostname-path [:nodeinfo :hostname])
(def id-path [:nodeinfo :node_id])
(def online?-path [:flags :online])

;; convert all date info to clj-time datetime objects
(defn value-coercer [key value]
  (if (or (= key :lastseen)
          (= key :firstseen)
          (= key :timestamp))
    (f/parse value)
    value))

(defn node-infos [url]
  (let [nodes (:nodes (json/read-str (slurp url)
                                     :key-fn keyword
                                     :value-fn value-coercer))]
    (vals nodes)))

(defn send-alert-requested? [node-info]
  (get-in node-info send-alerts?-path))

(defn node-online? [node-info]
  (get-in node-info online?-path))

(defn nodes-last-seen-in-interval [node-infos start-dt end-dt]
  (filter (fn [x] (and (send-alert-requested? x)
                       (t/within? start-dt end-dt (:lastseen x))
                       (not (node-online? x))))
          node-infos))

(def date-formatter (f/formatter "d.M.yyyy" (t/default-time-zone)))
(def time-formatter (f/formatter "H:m"  (t/default-time-zone)))

(defn send-notification-email
  "Assuming email-address (aka contact) in all given node-infos is the same."
  [node-infos email-config]
  (let [email-address (get-in (first node-infos) email-address-path)

        affected-routers-text (reduce (fn [previous-text node-info]
                                        (let [last-seen (:lastseen node-info)
                                              replacements {:last-seen-date (f/unparse date-formatter last-seen)
                                                            :last-seen-time (f/unparse time-formatter last-seen)
                                                            :node-name  (get-in node-info hostname-path)
                                                            :node-id (get-in node-info id-path)}]
                                          (str previous-text (c/render "Router \"{{&node-name}}\", zuletzt gemeldet am {{&last-seen-date}} um {{&last-seen-time}} Uhr.\nZur Karte: <https://map.kbu.freifunk.net/#!v:m;n:{{&node-id}}>\n\n" replacements))))
                                      "" node-infos)]
    (postal/send-message (:smtp email-config)
                         {:from (:from email-config)
                          :to (if DEBUG
                                (:from email-config)
                                email-address)
                          :subject (:subject email-config)
                          :body (c/render (:body email-config) {:node-list affected-routers-text})})))

(defn check
  "Sends notification emails to matching vanished node-owners."
  [interval]
  (let [config (load-config :file "/usr/local/etc/ff-monitor.edn")]
    (if (spec/valid? ::config config)
      (try
        (let [nodes (reduce (fn [x y]
                              (concat x (node-infos y))) [] (:nodes-urls config))
              vanished-nodes (nodes-last-seen-in-interval
                              nodes
                              (t/minus (l/local-now) (t/minutes interval))
                              (l/local-now))
              nodes-for-notification (filter (fn [x]
                                               (and (send-alert-requested? x)
                                                    (contains-valid-email-address?
                                                     (get-in x email-address-path))))
                                             vanished-nodes)
              grouped-by-email-address (group-by
                                        #(get-in % email-address-path)
                                        nodes-for-notification)]
          (log/info "Checking" (count nodes) "nodes.")
          (doseq [node-infos-for-email-address grouped-by-email-address]
            (send-notification-email
             (nth node-infos-for-email-address 1)
             (:email config)))
          (log/info "Sent"
                    (count grouped-by-email-address)
                    "notification email(s) for"
                    (count nodes-for-notification)
                    "vanished node(s) (using the given interval info)."))
        (catch Exception e (log/error e)))
      (do
        (log/error (str "Aborted. Invalid configuration file:\n"
                        (spec/explain-str ::config config)))
        (throw (Exception. (str "Aborted. Invalid configuration file:\n"
                                (spec/explain-str ::config config))))))))


(defn run-every-minutes [minutes f & args]
  (loop []
    (apply f args)
    (Thread/sleep (* 1000 60 minutes))
    (recur)))

(defn -main
  "No arguments supported yet."
  [& args]
  (let [interval 20]
    (try
      (run-every-minutes interval check interval)
      (catch Exception e (System/exit 2)))))

;; future plan is having two parameters
;; - time of last check
;; - max. duration where a node is allowed to not been seen before being vanished
