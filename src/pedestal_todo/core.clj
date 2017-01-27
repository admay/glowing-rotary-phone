(ns pedestal-todo.core
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as test]))

(defn response
  "Create a response"
  [status body & {:as headers}]
  {:status status :body body :headers headers})

(def ok       (partial response 200))
(def created  (partial response 201))
(def accepted (partial response 202))

;;;
;;; "Database" functions
;;;
(defonce database (atom {}))

(defn find-list-by-id
  "Query DB for a list"
  [dbval db-id]
  (get dbval db-id))

(defn find-list-item-by-ids
  "Query DB for list item"
  [dbval list-id item-id]
  (get-in dbval [list-id :items item-id] nil))

(defn list-item-add
  "Add a list item to DB"
  [dbval list-id item-id new-item]
  (if (contains? dbval list-id)
    (assoc-in dbval [list-id :items item-id] new-item)
    dbval))

(def db-interceptor
  {:name :database-interceptor
   :enter
   (fn [context]
     (update context :request assoc :database @database))
   :leave
   (fn [context]
     (if-let [[op & args] (:tx-data context)]
       (do
         (apply swap! database op args)
         (assoc-in context [:request :database] @database))
       context))})

;;;
;;; Domain functions
;;;
(defn make-list
  "Create list map"
  [name]
  {:name  name
   :items {}})

(defn make-list-item
  "Create list item map"
  [name]
  {:name  name
   :done? false})

;;;
;;; API Interceptors
;;;
(def entity-render
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})

(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [name     (get-in context [:request :query-params :name] "Unnamed List")
           new-list (make-list name)
           db-id    (str (gensym "l"))
           url      (route/url-for :list-view :params {:list-id db-id})]
       (assoc context
              :response (created new-list "Location" url)
              :tx-data [assoc db-id new-list])))})

(def list-view
  {:name :list-view
   :enter
   (fn [context]
     (if-let [db-id (get-in context [:request :params :list-id])]
       (if-let [l (find-list-by-id (get-in context [:request :database]) db-id)]
         (assoc context :result l)
         context)
       context))})

(def list-item-view
  {:name :list-item-view
   :leave
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (if-let [item-id (get-in context [:request :path-params :item-id])]
         (if-let [item (find-list-item-by-ids (get-in context [:request :database]) list-id item-id)]
           (assoc context :result item)
           context)
         context)
       context))})

(def list-item-create
  {:name :list-item-create
   :enter
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (let [nm       (get-in context [:request :query-params :name] "Unnamed Item")
             new-item (make-list-item nm)
             item-id  (str (gensym "i"))]
         (-> context
             (assoc :tx-data  [list-item-add list-id item-id new-item])
             (assoc-in [:request :path-params :item-id] item-id)))
       context))})

(def routes
  (route/expand-routes
   #{["/todo"                 :post   [db-interceptor list-create]]
     ["/todo"                 :get    echo :route-name :list-query-form]
     ["/todo/:list-id"        :get    [entity-render db-interceptor list-view]]
     ["/todo/:list-id"        :post   [entity-render list-item-view db-interceptor list-item-create]]
     ["/todo/:list-id/:item"  :get    [entity-render list-item-view]]
     ["/todo/:list-id/:item"  :put    echo :route-name :list-item-update]
     ["/todo/:list-id/:item"  :delete echo :route-name :list-item-delete]}))

(def service-map
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8890})

(defn start []
  (http/start (http/create-server service-map)))

;; For interactive development
(defonce server (atom nil))

(defn start-dev []
  (reset! server
          (http/start (http/create-server
                       (assoc service-map
                              ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (stop-dev)
  (start-dev))

(defn test-request [verb url]
  (io.pedestal.test/response-for (::http/service-fn @server) verb url))

(comment

  Notes on routes with pedestal.

  Bare minimum
  ["/users" :get view-users]
  ["/route" :type handler-function]

  *:type can be :get, :post, :put, :delete, :options, :head, :any, or :options

  *The handler can be either a fully defined interceptor or a function that returns one.
  ["/users" :get (view-users db-conn)]

  *You can also pass in a vector of handlers to chain interceptors
  ["/users" :post [create-entity (db-interceptor db-conn) store-entity]]

  *Anonymous functions can also be used as handlers
  ["/echo" :get (fn [body] {:status 200 :body body}) :route-name :echo]

  *Single path parameters are denoted in the route as keywords
  ["/users/:user-id" :get view-user]

  *The request will then contain a map called path-params that looks like,
  {:path-params {:user-id "foobar"}}

  *Using an asterisk will catch-all params
  ["/users/:user-id/profile/*subpage" :get view-user-resource]

  {:path-params {:user-id "foobar" :subpage "photos/summer.jpg"}}

  *Query parameters are stored in the :query-params map
  "http://website.io/search?q=science" => {:query-params {:q "science"}}

  *Since things like adding DB connections and auth are so common...
  (def common-interceptors [inject-connect authorize-user ])

  ["/user/:user-id/settings" :post (conj common-interceptors view-user-settings)]

  *You can also constrain the routes
  ["/users/:user-id" :get view-user :constraints {:user-id #"[0-9]+"}]

  (def numeric #"[0-9]+")
  (def user-id {:user-id numeric})

  ["/user/:user-id" :get  view-user   :constraints user-id]
  ["/user/:user-id" :post update-user :constraints user-id]

  *Constraints are used only to reject bad requests

  *Routes must have names. If you don't provide one, pedestal will use the name of the
  *last interceptor in the chain.
  ["/user" :get view-user :route-name :view-user-profile]
  ["/user" :get view-user] => :route-name will be :view-user

  *There is an order to this:
  1. Path
  2. Verb
  3. Interceptors
  4. Route name
  5. Constraints

  * This won't work in table syntax. Both rows get the same automatic
  * route name.
  ["/users" :get user-api-handler]
  ["/users" :post user-api-handler]

  *Instead, define your own route names
  ["/users" :get  user-api-handler :route-name :users-view]
  ["/users" :post user-api-handler :route-name :user-create]

  *If one routes has multiple verbs, you can define them using a map
  ["/users" {:get user-api-handler
             :post [:create-user user-api-handler]}]

  *Route tables are also used for URL generation

  *The `url-for-routes` function takes a parsed route table and returns a URL generating function
  (def app-routes
    (table/table-routes
     {}
     [["/user"                   :get  user-search-form]
      ["/user/:user-id"          :get  view-user        :route-name :show-user-profile]
      ["/user/:user-id/timeline" :post post-timeline    :route-name :timeline]
      ["/user/:user-id/profile"  :put  update-profile]]))

  (def url-for (route/url-for-routes app-routes))

  (url-for :user-search-form)
  ;; => "/user"

  (url-for :view-user :params {:user-id "foobar"})
  ;; => "/user/foobar"

  *url-for only provides a URL, not a complete action
  *form-action-for-routes can be used for that
  (def form-action (route/form-action-for-routes app-routes))
  (form-action :timeline :params {:user-id "foobar"})
  ;; => {:method "post", :action "/user/:user-id/timelie"}

  *If the action is anything other than get or post, it'll be converted to post
  *with the true action added as a query parameter
  (form-action :update-profile :params {:user-id 12345})
  ;; => {:method "post", :action "/user/12345/profile?_method=put"}

  *Map tree router is the fastest router in Pedestal.
  *It forbids dynamic path segments like wildcards and path parameters
  *If any of those are used, the prefix tree routers will be used instead.
)
