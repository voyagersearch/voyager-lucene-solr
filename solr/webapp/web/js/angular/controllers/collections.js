/*
 Licensed to the Apache Software Foundation (ASF) under one or more
 contributor license agreements.  See the NOTICE file distributed with
 this work for additional information regarding copyright ownership.
 The ASF licenses this file to You under the Apache License, Version 2.0
 (the "License"); you may not use this file except in compliance with
 the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

// @todo test optimize (delete stuff, watch button appear, test button/form)
solrAdminApp.controller('CollectionsController',
    function($scope, $routeParams, $location, $timeout, Collections, Zookeeper, Constants){
      $scope.resetMenu("collections", Constants.IS_ROOT_PAGE);

      $scope.refresh = function() {

          Collections.status(function (data) {
              $scope.collections = [];
              for (var name in data.cluster.collections) {
                  var collection = data.cluster.collections[name];
                  collection.name = name;
                  var shards = collection.shards;
                  collection.shards = [];
                  for (var shardName in shards) {
                      var shard = shards[shardName];
                      shard.name = shardName;
                      shard.collection = collection.name;
                      var replicas = shard.replicas;
                      shard.replicas = [];
                      for (var replicaName in replicas) {
                          var replica = replicas[replicaName];
                          replica.name = replicaName;
                          replica.collection = collection.name;
                          replica.shard = shard.name;
                          shard.replicas.push(replica);
                      }
                      collection.shards.push(shard);
                  }
                  $scope.collections.push(collection);
                  if ($routeParams.collection == name) {
                      $scope.collection = collection;
                  }
              }
              if ($routeParams.collection && !$scope.collection) {
                  alert("No collection called " + $routeParams.collection)
                  $location.path("/~collections");
              }
              $scope.liveNodes = data.cluster.liveNodes;
          });
          Zookeeper.configs(function(data) {
              $scope.configs = [];
              var items = data.tree[0].children;
              for (var i in items) {
                  $scope.configs.push({name: items[i].data.title});
              }
          });
      };

      $scope.hideAll = function() {
          $scope.showRename = false;
          $scope.showAdd = false;
          $scope.showDelete = false;
          $scope.showSwap = false;
          $scope.showCreateAlias = false;
          $scope.showDeleteAlias = false;
      };

      $scope.showAddCollection = function() {
        $scope.hideAll();
        $scope.showAdd = true;
        $scope.newCollection = {
          name: "new_collection",
          routerName: "compositeId",
          numShards: 1,
          configName: "",
          replicationFactor: 1,
          maxShardsPerNode: 1
        };
      };

      $scope.toggleCreateAlias = function() {
        $scope.hideAll();
        $scope.showCreateAlias = true;
      }

      $scope.toggleDeleteAlias = function() {
        $scope.hideAll();
        $scope.showDeleteAlias = true;
        Zookeeper.aliases({}, function(data){
          if (Object.keys(data.aliases).length == 0) {
            delete $scope.aliases;
          } else {
            $scope.aliases = data.aliases;
          }
        });

      }

      $scope.cancelCreateAlias = $scope.cancelDeleteAlias = function() {
        $scope.hideAll();
      }

      $scope.createAlias = function() {
        var collections = $scope.aliasCollections.join(",");
        Collections.createAlias({name: $scope.aliasToCreate, collections: collections}, function(data) {
          $scope.hideAll();
        });
      }
      $scope.deleteAlias = function() {
        Collections.deleteAlias({name: $scope.aliasToDelete}, function(data) {
          $scope.hideAll();
        });

      };
      $scope.addCollection = function() {
        if (!$scope.newCollection.name) {
          $scope.addMessage = "Please provide a core name";
        } else if (false) { //@todo detect whether core exists
          $scope.AddMessage = "A core with that name already exists";
        } else {
            var coll = $scope.newCollection;
            var params = {
                name: coll.name,
                "router.name": coll.routerName,
                numShards: coll.numShards,
                "collection.configName": coll.configName,
                replicationFactor: coll.replicationFactor,
                maxShardsPerNode: coll.maxShardsPerNode
            };
            if (coll.shards) params.shards = coll.shards;
            if (coll.routerField) params.routerField = coll.routerField;
            if (coll.routerName) params.routerName = coll.routerName;
            Collections.add(params, function(data) {
              $scope.cancelAddCollection();
              $scope.resetMenu("collections", Constants.IS_ROOT_PAGE);
              $location.path("/~collections/" + $scope.newCollection.name);
            });
        }
      };

      $scope.cancelAddCollection = function() {
        delete $scope.addMessage;
        $scope.showAdd = false;
      };

      $scope.showDeleteCollection = function() {
          $scope.hideAll();
          if ($scope.collection) {
              $scope.showDelete = true;
          } else {
              alert("No collection selected.");
          }
      };

      $scope.deleteCollection = function() {
        if ($scope.collection.name == $scope.collectionDeleteConfirm) {
            Collections.delete({name: $scope.collection.name}, function (data) {
                $location.path("/~collections");
            });
        } else {
            $scope.deleteMessage = "Collection names do not match.";
        }
      };

      $scope.reloadCollection = function() {
        if (!$scope.collection) {
            alert("No collection selected.");
            return;
        }
        Collections.reload({name: $scope.collection.name},
          function(successData) {
            $scope.reloadSuccess = true;
            $timeout(function() {$scope.reloadSuccess=false}, 1000);
          },
          function(failureData) {
            $scope.reloadFailure = true;
            $timeout(function() {$scope.reloadFailure=false}, 1000);
            $location.path("/~collections");
          });
      };

      $scope.toggleAddReplica = function(shard) {
          $scope.hideAll();
          shard.showAdd = !shard.showAdd;
          delete $scope.addReplicaMessage;

          Zookeeper.liveNodes({}, function(data) {
            $scope.nodes = [];
            var children = data.tree[0].children;
            for (var child in children) {
              $scope.nodes.push(children[child].data.title);
            }
          });
      };

      $scope.toggleRemoveReplica = function(replica) {
          $scope.hideAll();
          replica.showRemove = !replica.showRemove;
      };

      $scope.deleteReplica = function(replica) {
        alert("DELETE");
        Collections.deleteReplica({collection: replica.collection, shard:replica.shard, replica:replica.name}, function(data) {
          replica.deleted = true;
          $timeout(function() {
            $scope.refresh();
          }, 2000);
        });
      }
      $scope.addReplica = function(shard) {
        var params = {
          collection: shard.collection,
          shard: shard.name,
        }
        if (shard.replicaNodeName && shard.replicaNodeName != "") {
          params.node = shard.replicaNodeName;
        }
        Collections.addReplica(params, function(data) {
          shard.replicaAdded = true;
          $timeout(function () {
            shard.replicaAdded = false;
            shard.showAdd = false;
            $$scope.refresh();
          }, 2000);
        });
      };

      $scope.toggleShard = function(shard) {
          shard.show = !shard.show;
      }

      $scope.toggleReplica = function(replica) {
          replica.show = !replica.show;
      }

      $scope.refresh();
    }
);

var flatten = function(data) {
    var list = [];
    for (var name in data) {
       var entry = data[name];
        entry.name = name;
        list.push(entry);
    }
    return list;
}
