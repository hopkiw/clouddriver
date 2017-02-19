/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.elasticsearch.ops;

import com.netflix.spinnaker.clouddriver.core.services.Front50Service;
import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.elasticsearch.EntityRefIdBuilder;
import com.netflix.spinnaker.clouddriver.elasticsearch.descriptions.BulkUpsertEntityTagsDescription;
import com.netflix.spinnaker.clouddriver.elasticsearch.model.ElasticSearchEntityTagsProvider;
import com.netflix.spinnaker.clouddriver.model.EntityTags;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import com.netflix.spinnaker.clouddriver.security.AccountCredentials;
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider;
import com.netflix.spinnaker.security.AuthenticatedRequest;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.lang.String.format;

public class BulkUpsertEntityTagsAtomicOperation implements AtomicOperation<BulkUpsertEntityTagsAtomicOperationResult> {

  private static final String BASE_PHASE = "ENTITY_TAGS";

  private final Front50Service front50Service;
  private final AccountCredentialsProvider accountCredentialsProvider;
  private final ElasticSearchEntityTagsProvider entityTagsProvider;
  private final BulkUpsertEntityTagsDescription bulkUpsertEntityTagsDescription;

  public BulkUpsertEntityTagsAtomicOperation(Front50Service front50Service,
                                             AccountCredentialsProvider accountCredentialsProvider,
                                             ElasticSearchEntityTagsProvider entityTagsProvider,
                                             BulkUpsertEntityTagsDescription bulkUpsertEntityTagsDescription) {
    this.front50Service = front50Service;
    this.accountCredentialsProvider = accountCredentialsProvider;
    this.entityTagsProvider = entityTagsProvider;
    this.bulkUpsertEntityTagsDescription = bulkUpsertEntityTagsDescription;
  }

  public BulkUpsertEntityTagsAtomicOperationResult operate(List priorOutputs) {
    BulkUpsertEntityTagsAtomicOperationResult result = new BulkUpsertEntityTagsAtomicOperationResult();
    List<EntityTags> entityTags = bulkUpsertEntityTagsDescription.entityTags;

    addTagIdsIfMissing(entityTags, result);

    mergeTags(bulkUpsertEntityTagsDescription);

    Date now = new Date();
    getTask().updateStatus(BASE_PHASE, "Retrieving current entity tags");

    Map<String, EntityTags> existingTags = retrieveExistingTags(entityTags);

    getTask().updateStatus(BASE_PHASE, "Merging existing tags and metadata");

    entityTags.forEach(tag -> mergeExistingTagsAndMetadata(now, existingTags.get(tag.getId()), tag, bulkUpsertEntityTagsDescription.isPartial));

    Map<String, EntityTags> durableTags = front50Service.batchUpdate(new ArrayList<>(entityTags))
        .stream().collect(Collectors.toMap(EntityTags::getId, Function.identity()));

    updateMetadataFromDurableTagsAndIndex(entityTags, durableTags, result);
    result.upserted.addAll(entityTags);
    return result;
  }

  private Map<String, EntityTags> retrieveExistingTags(List<EntityTags> entityTags) {
    return front50Service.getAllEntityTagsById(
      entityTags.stream().map(EntityTags::getId).collect(Collectors.toList())
    ).stream().collect(Collectors.toMap(EntityTags::getId, Function.identity()));
  }

  private void addTagIdsIfMissing(List<EntityTags> entityTags, BulkUpsertEntityTagsAtomicOperationResult result) {
    Collection<EntityTags> failed = new ArrayList<>();
    entityTags.forEach(tag -> {
      if (tag.getId() == null) {
        try {
          EntityRefIdBuilder.EntityRefId entityRefId = entityRefId(accountCredentialsProvider, tag);
          tag.setId(entityRefId.id);
          tag.setIdPattern(entityRefId.idPattern);
        } catch (Exception e) {
          getTask().updateStatus(BASE_PHASE, format("Failed to add tag ID: %s", tag.getId()));
          failed.add(tag);
          result.failures.add(new BulkUpsertEntityTagsAtomicOperationResult.UpsertFailureResult(tag, e));
        }
      }
    });
    entityTags.removeAll(failed);
  }

  private void updateMetadataFromDurableTagsAndIndex(List<EntityTags> entityTags, Map<String, EntityTags> durableTags, BulkUpsertEntityTagsAtomicOperationResult result) {
    Collection<EntityTags> failed = new ArrayList<>();
    entityTags.forEach(tag -> {
      try {
        EntityTags durableTag = durableTags.get(tag.getId());
        tag.setLastModified(durableTag.getLastModified());
        tag.setLastModifiedBy(durableTag.getLastModifiedBy());
      } catch (Exception e) {
        getTask().updateStatus(BASE_PHASE, format("Failed to update %s in ElasticSearch", tag.getId()));
        failed.add(tag);
        result.failures.add(new BulkUpsertEntityTagsAtomicOperationResult.UpsertFailureResult(tag, e));
      }
    });
    entityTags.removeAll(failed);
    getTask().updateStatus(BASE_PHASE, "Indexing tags in ElasticSearch");
    entityTagsProvider.bulkIndex(entityTags);

    entityTags.forEach(tag -> {
      try {
        entityTagsProvider.verifyIndex(tag);
      } catch (Exception e) {
        getTask().updateStatus(BASE_PHASE, format("Failed to update %s in ElasticSearch", tag.getId()));
        failed.add(tag);
      }
    });
    entityTags.removeAll(failed);
  }

  public static EntityRefIdBuilder.EntityRefId entityRefId(AccountCredentialsProvider accountCredentialsProvider,
                                                           EntityTags description) {
    EntityTags.EntityRef entityRef = description.getEntityRef();
    String entityRefAccount = entityRef.getAccount();
    String entityRefAccountId = entityRef.getAccountId();

    if (entityRefAccount != null && entityRefAccountId == null) {
      // add `accountId` if not explicitly provided
      AccountCredentials accountCredentials = accountCredentialsProvider.getCredentials(entityRefAccount);
      entityRefAccountId = accountCredentials.getAccountId();
      entityRef.setAccountId(entityRefAccountId);
    }

    if (entityRefAccount == null && entityRefAccountId != null) {
      // add `account` if not explicitly provided
      AccountCredentials accountCredentials = lookupAccountCredentials(accountCredentialsProvider, entityRefAccountId);
      if (accountCredentials != null) {
        entityRefAccount = accountCredentials.getName();
        entityRef.setAccount(entityRefAccount);
      }
    }

    return EntityRefIdBuilder.buildId(
      entityRef.getCloudProvider(),
      entityRef.getEntityType(),
      entityRef.getEntityId(),
      Optional.ofNullable(entityRefAccountId).orElse(entityRefAccount),
      entityRef.getRegion()
    );
  }

  public static void mergeExistingTagsAndMetadata(Date now,
                                                  EntityTags currentTags,
                                                  EntityTags updatedTags,
                                                  boolean isPartial) {

    if (currentTags == null) {
      addTagMetadata(now, updatedTags);
      return;
    }

    if (!isPartial) {
      replaceTagContents(currentTags, updatedTags);
    }

    updatedTags.setTagsMetadata(
      currentTags.getTagsMetadata() == null ? new ArrayList<>() : currentTags.getTagsMetadata()
    );

    updatedTags.getTags().forEach(tag -> updatedTags.putEntityTagMetadata(tagMetadata(tag.getName(), now)));

    currentTags.getTags().forEach(updatedTags::putEntityTagIfAbsent);
  }

  private static void mergeTags(BulkUpsertEntityTagsDescription bulkUpsertEntityTagsDescription) {
    List<EntityTags> toRemove = new ArrayList<>();
    bulkUpsertEntityTagsDescription.entityTags.forEach(tag -> {
      Collection<EntityTags> matches = bulkUpsertEntityTagsDescription.entityTags
        .stream()
        .filter(t ->
          t.getId().equals(tag.getId()) && !toRemove.contains(t) && !tag.equals(t)
        )
        .collect(Collectors.toList());
      if (matches.size() > 1) {
        matches.forEach(m -> tag.getTags().addAll(m.getTags()));
        toRemove.addAll(matches);
      }
    });
    bulkUpsertEntityTagsDescription.entityTags.removeAll(toRemove);
  }

  private static void replaceTagContents(EntityTags currentTags, EntityTags entityTagsDescription) {
    Map<String, EntityTags.EntityTag> entityTagsByName = entityTagsDescription.getTags().stream()
      .collect(Collectors.toMap(EntityTags.EntityTag::getName, x -> x));

    currentTags.setTags(entityTagsDescription.getTags());
    for (EntityTags.EntityTagMetadata entityTagMetadata : currentTags.getTagsMetadata()) {
      if (!entityTagsByName.containsKey(entityTagMetadata.getName())) {
        currentTags.removeEntityTagMetadata(entityTagMetadata.getName());
      }
    }
  }

  private static EntityTags.EntityTagMetadata tagMetadata(String tagName, Date now) {
    String user = AuthenticatedRequest.getSpinnakerUser().orElse("unknown");

    EntityTags.EntityTagMetadata metadata = new EntityTags.EntityTagMetadata();
    metadata.setName(tagName);
    metadata.setCreated(now.getTime());
    metadata.setLastModified(now.getTime());
    metadata.setCreatedBy(user);
    metadata.setLastModifiedBy(user);

    return metadata;
  }

  private static void addTagMetadata(Date now, EntityTags entityTags) {
    entityTags.setTagsMetadata(new ArrayList<>());
    entityTags.getTags().forEach(tag -> entityTags.putEntityTagMetadata(tagMetadata(tag.getName(), now)));
  }

  private static AccountCredentials lookupAccountCredentials(AccountCredentialsProvider accountCredentialsProvider,
                                                             String entityRefAccountId) {
    return accountCredentialsProvider.getAll().stream()
      .filter(c -> entityRefAccountId.equals(c.getAccountId()))
      .findFirst()
      .orElseThrow(() -> new IllegalArgumentException("No credentials found for accountId '" + entityRefAccountId + "'"));
  }

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }
}