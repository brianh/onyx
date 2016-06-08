(ns onyx.log.commands.leave-cluster
  (:require [clojure.core.async :refer [chan go >! <! >!! close!]]
            [clojure.set :refer [union difference map-invert]]
            [clojure.data :refer [diff]]
            [com.stuartsierra.component :as component]
            [schema.core :as s]
            [onyx.schema :refer [Replica LogEntry Reactions ReplicaDiff State]]
            [onyx.extensions :as extensions]
            [onyx.log.commands.common :as common]
            [onyx.log.commands.kill-job :as kill]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.scheduling.common-job-scheduler :refer [reconfigure-cluster-workload]]))

(s/defmethod extensions/apply-log-entry :leave-cluster :- Replica
  [{:keys [args]} :- LogEntry replica]
  (let [{:keys [id]} args
        group-id (get-in replica [:groups-reverse-index id])]
    (-> replica
        (kill/enforce-flux-policy id)
        (update-in [:peers] (partial remove #(= % id)))
        (update-in [:peers] vec)
        (update-in [:orphaned-peers group-id] (partial remove #(= % id)))
        (update-in [:orphaned-peers group-id] vec)
        (update-in [:peer-state] dissoc id)
        (update-in [:peer-sites] dissoc id)
        (update-in [:peer-tags] dissoc id)
        ((fn [rep] (if group-id (update-in rep [:groups-index group-id] disj id) rep)))
        (update-in [:groups-reverse-index] dissoc id)
        (common/remove-peers id)
        (reconfigure-cluster-workload))))

(s/defmethod extensions/replica-diff :leave-cluster :- ReplicaDiff
  [{:keys [args]} old new]
  {:died (:id args)})

(s/defmethod extensions/reactions [:leave-cluster :peer] :- Reactions
  [{:keys [args]} old new diff state]
  (when (and (= (:id state) (:id args))
             (:restart? args))
    [{:fn :add-virtual-peer
      :args {:id (:restarted-id args)
             :group-id (:group-id state)
             :peer-site (:peer-site state)
             :tags (:onyx.peer/tags (:opts state))}}]))

(s/defmethod extensions/fire-side-effects! [:leave-cluster :peer] :- State
  [{:keys [args]} old new diff state]
  (when (= (:id state) (:id args))
    ;; TODO, possibly allow quick reboot here if this is us
    )
  (common/start-new-lifecycle old new diff state :peer-reallocated))
