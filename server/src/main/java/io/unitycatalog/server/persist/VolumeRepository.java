package io.unitycatalog.server.persist;

import io.unitycatalog.server.exception.BaseException;
import io.unitycatalog.server.exception.ErrorCode;
import io.unitycatalog.server.model.*;
import io.unitycatalog.server.persist.dao.CatalogInfoDAO;
import io.unitycatalog.server.persist.dao.SchemaInfoDAO;
import io.unitycatalog.server.persist.dao.VolumeInfoDAO;
import io.unitycatalog.server.persist.utils.FileOperations;
import io.unitycatalog.server.persist.utils.PagedListingHelper;
import io.unitycatalog.server.persist.utils.TransactionManager;
import io.unitycatalog.server.utils.IdentityUtils;
import io.unitycatalog.server.utils.ValidationUtils;
import java.util.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VolumeRepository {
  private static final Logger LOGGER = LoggerFactory.getLogger(VolumeRepository.class);
  private final Repositories repositories;
  private final SessionFactory sessionFactory;
  private final FileOperations fileOperations;
  private static final PagedListingHelper<VolumeInfoDAO> LISTING_HELPER =
      new PagedListingHelper<>(VolumeInfoDAO.class);

  public VolumeRepository(Repositories repositories, SessionFactory sessionFactory) {
    this.repositories = repositories;
    this.sessionFactory = sessionFactory;
    this.fileOperations = repositories.getFileOperations();
  }

  public VolumeInfo createVolume(CreateVolumeRequestContent createVolumeRequest) {
    ValidationUtils.validateSqlObjectName(createVolumeRequest.getName());
    String volumeFullName =
        createVolumeRequest.getCatalogName()
            + "."
            + createVolumeRequest.getSchemaName()
            + "."
            + createVolumeRequest.getName();
    String callerId = IdentityUtils.findPrincipalEmailAddress();
    Long createTime = System.currentTimeMillis();
    VolumeInfo volumeInfo = new VolumeInfo();
    volumeInfo.setVolumeId(UUID.randomUUID().toString());
    volumeInfo.setCatalogName(createVolumeRequest.getCatalogName());
    volumeInfo.setSchemaName(createVolumeRequest.getSchemaName());
    volumeInfo.setName(createVolumeRequest.getName());
    volumeInfo.setComment(createVolumeRequest.getComment());
    volumeInfo.setFullName(volumeFullName);
    volumeInfo.setOwner(callerId);
    volumeInfo.setCreatedAt(createTime);
    volumeInfo.setCreatedBy(callerId);
    volumeInfo.setUpdatedAt(createTime);
    volumeInfo.setUpdatedBy(callerId);
    volumeInfo.setVolumeType(createVolumeRequest.getVolumeType());
    if (VolumeType.MANAGED.equals(createVolumeRequest.getVolumeType())) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Managed volume creation is not supported");
    }
    if (createVolumeRequest.getStorageLocation() == null) {
      throw new BaseException(
          ErrorCode.INVALID_ARGUMENT, "Storage location is required for external volume");
    }
    volumeInfo.setStorageLocation(createVolumeRequest.getStorageLocation());
    VolumeInfoDAO volumeInfoDAO = VolumeInfoDAO.from(volumeInfo);

    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          SchemaInfoDAO schemaInfoDAO =
              repositories
                  .getSchemaRepository()
                  .getSchemaDAO(
                      session,
                      createVolumeRequest.getCatalogName(),
                      createVolumeRequest.getSchemaName());
          if (schemaInfoDAO == null) {
            throw new BaseException(
                ErrorCode.NOT_FOUND,
                "Schema not found: "
                    + createVolumeRequest.getCatalogName()
                    + "."
                    + createVolumeRequest.getSchemaName());
          }
          if (getVolumeDAO(
                  session,
                  createVolumeRequest.getCatalogName(),
                  createVolumeRequest.getSchemaName(),
                  createVolumeRequest.getName())
              != null) {
            throw new BaseException(
                ErrorCode.ALREADY_EXISTS, "Volume already exists: " + volumeFullName);
          }
          volumeInfoDAO.setSchemaId(schemaInfoDAO.getId());
          session.persist(volumeInfoDAO);
          LOGGER.info("Added volume: {}", volumeInfo.getName());
          return convertFromDAO(
              volumeInfoDAO,
              createVolumeRequest.getCatalogName(),
              createVolumeRequest.getSchemaName());
        },
        "Failed to create volume",
        /* readOnly = */ false);
  }

  public VolumeInfo getVolume(String fullName) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          String[] namespace = fullName.split("\\.");
          if (namespace.length != 3) {
            throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid volume name: " + fullName);
          }
          String catalogName = namespace[0];
          String schemaName = namespace[1];
          String volumeName = namespace[2];
          return convertFromDAO(
              getVolumeDAO(session, catalogName, schemaName, volumeName), catalogName, schemaName);
        },
        "Failed to get volume",
        /* readOnly = */ true);
  }

  public VolumeInfoDAO getVolumeDAO(
      Session session, String catalogName, String schemaName, String volumeName) {
    SchemaInfoDAO schemaInfo =
        repositories.getSchemaRepository().getSchemaDAO(session, catalogName, schemaName);
    if (schemaInfo == null) {
      throw new BaseException(
          ErrorCode.NOT_FOUND, "Schema not found: " + catalogName + "." + schemaName);
    }
    return getVolumeDAO(session, schemaInfo.getId(), volumeName);
  }

  public VolumeInfoDAO getVolumeDAO(Session session, UUID schemaId, String volumeName) {
    Query<VolumeInfoDAO> query =
        session.createQuery(
            "FROM VolumeInfoDAO WHERE name = :name and schemaId = :schemaId", VolumeInfoDAO.class);
    query.setParameter("name", volumeName);
    query.setParameter("schemaId", schemaId);
    query.setMaxResults(1);
    return query.uniqueResult();
  }

  public VolumeInfo getVolumeById(String volumeId) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          VolumeInfoDAO volumeInfoDAO = session.get(VolumeInfoDAO.class, UUID.fromString(volumeId));
          if (volumeInfoDAO == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Volume not found: " + volumeId);
          }
          SchemaInfoDAO schemaInfoDAO =
              session.get(SchemaInfoDAO.class, volumeInfoDAO.getSchemaId());
          if (schemaInfoDAO == null) {
            throw new BaseException(
                ErrorCode.NOT_FOUND, "Schema not found: " + volumeInfoDAO.getSchemaId());
          }
          CatalogInfoDAO catalogInfoDAO =
              session.get(CatalogInfoDAO.class, schemaInfoDAO.getCatalogId());
          if (catalogInfoDAO == null) {
            throw new BaseException(
                ErrorCode.NOT_FOUND, "Catalog not found: " + schemaInfoDAO.getCatalogId());
          }
          VolumeInfo volumeInfo = volumeInfoDAO.toVolumeInfo();
          volumeInfo.setSchemaName(schemaInfoDAO.getName());
          volumeInfo.setCatalogName(catalogInfoDAO.getName());
          return volumeInfo;
        },
        "Failed to get volume by ID",
        /* readOnly = */ true);
  }

  private void addNamespaceData(VolumeInfo volumeInfo, String catalogName, String schemaName) {
    volumeInfo.setCatalogName(catalogName);
    volumeInfo.setSchemaName(schemaName);
    volumeInfo.setFullName(catalogName + "." + schemaName + "." + volumeInfo.getName());
  }

  public UUID getSchemaId(Session session, String catalogName, String schemaName) {
    SchemaInfoDAO schemaInfo =
        repositories.getSchemaRepository().getSchemaDAO(session, catalogName, schemaName);
    if (schemaInfo == null) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Schema not found: " + schemaName);
    }
    return schemaInfo.getId();
  }

  /**
   * Return the list of volumes in ascending order of volume name.
   *
   * @param catalogName
   * @param schemaName
   * @param maxResults
   * @param pageToken
   * @param includeBrowse
   * @return
   */
  public ListVolumesResponseContent listVolumes(
      String catalogName,
      String schemaName,
      Optional<Integer> maxResults,
      Optional<String> pageToken,
      Optional<Boolean> includeBrowse) {
    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          UUID schemaId = getSchemaId(session, catalogName, schemaName);
          return listVolumes(session, schemaId, catalogName, schemaName, maxResults, pageToken);
        },
        "Failed to list volumes",
        /* readOnly = */ true);
  }

  public ListVolumesResponseContent listVolumes(
      Session session,
      UUID schemaId,
      String catalogName,
      String schemaName,
      Optional<Integer> maxResults,
      Optional<String> pageToken) {
    List<VolumeInfoDAO> volumeInfoDAOList =
        LISTING_HELPER.listEntity(session, maxResults, pageToken, schemaId);
    String nextPageToken = LISTING_HELPER.getNextPageToken(volumeInfoDAOList, maxResults);
    List<VolumeInfo> result = new ArrayList<>();
    for (VolumeInfoDAO volumeInfoDAO : volumeInfoDAOList) {
      VolumeInfo volumeInfo = volumeInfoDAO.toVolumeInfo();
      addNamespaceData(volumeInfo, catalogName, schemaName);
      result.add(volumeInfo);
    }
    return new ListVolumesResponseContent().volumes(result).nextPageToken(nextPageToken);
  }

  private VolumeInfo convertFromDAO(
      VolumeInfoDAO volumeInfoDAO, String catalogName, String schemaName) {
    VolumeInfo volumeInfo = volumeInfoDAO.toVolumeInfo();
    volumeInfo.setCatalogName(catalogName);
    volumeInfo.setSchemaName(schemaName);
    volumeInfo.setFullName(catalogName + "." + schemaName + "." + volumeInfo.getName());
    return volumeInfo;
  }

  public VolumeInfo updateVolume(String name, UpdateVolumeRequestContent updateVolumeRequest) {
    if (updateVolumeRequest.getNewName() != null) {
      ValidationUtils.validateSqlObjectName(updateVolumeRequest.getNewName());
    }
    String callerId = IdentityUtils.findPrincipalEmailAddress();
    String[] namespace = name.split("\\.");
    String catalog = namespace[0], schema = namespace[1], volume = namespace[2];

    return TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          VolumeInfoDAO volumeInfo = getVolumeDAO(session, catalog, schema, volume);
          if (volumeInfo == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Volume not found: " + name);
          }
          if (updateVolumeRequest.getNewName() != null) {
            VolumeInfoDAO existingVolume =
                getVolumeDAO(session, catalog, schema, updateVolumeRequest.getNewName());
            if (existingVolume != null) {
              throw new BaseException(
                  ErrorCode.ALREADY_EXISTS,
                  "Volume already exists: " + updateVolumeRequest.getNewName());
            }
          }
          if (updateVolumeRequest.getNewName() == null
              && updateVolumeRequest.getComment() == null) {
            return convertFromDAO(volumeInfo, catalog, schema);
          }
          if (updateVolumeRequest.getNewName() != null) {
            volumeInfo.setName(updateVolumeRequest.getNewName());
          }
          if (updateVolumeRequest.getComment() != null) {
            volumeInfo.setComment(updateVolumeRequest.getComment());
          }
          volumeInfo.setUpdatedAt(new Date());
          volumeInfo.setUpdatedBy(callerId);
          session.merge(volumeInfo);
          LOGGER.info("Updated volume: {}", volumeInfo.getName());
          return convertFromDAO(volumeInfo, catalog, schema);
        },
        "Failed to update volume",
        /* readOnly = */ false);
  }

  public void deleteVolume(String name) {
    TransactionManager.executeWithTransaction(
        sessionFactory,
        session -> {
          String[] namespace = name.split("\\.");
          if (namespace.length != 3) {
            throw new BaseException(ErrorCode.INVALID_ARGUMENT, "Invalid volume name: " + name);
          }
          String catalog = namespace[0], schema = namespace[1], volume = namespace[2];
          SchemaInfoDAO schemaInfo =
              repositories.getSchemaRepository().getSchemaDAO(session, catalog, schema);
          if (schemaInfo == null) {
            throw new BaseException(
                ErrorCode.NOT_FOUND, "Schema not found: " + catalog + "." + schema);
          }
          deleteVolume(session, schemaInfo.getId(), volume);
          return null;
        },
        "Failed to delete volume",
        /* readOnly = */ false);
  }

  public void deleteVolume(Session session, UUID schemaId, String volumeName) {
    VolumeInfoDAO volumeInfoDAO = getVolumeDAO(session, schemaId, volumeName);
    if (volumeInfoDAO == null) {
      throw new BaseException(ErrorCode.NOT_FOUND, "Volume not found: " + volumeName);
    }
    if (VolumeType.MANAGED.getValue().equals(volumeInfoDAO.getVolumeType())) {
      try {
        fileOperations.deleteDirectory(volumeInfoDAO.getStorageLocation());
      } catch (Exception e) {
        LOGGER.error("Error deleting volume directory", e);
      }
    }
    session.remove(volumeInfoDAO);
    LOGGER.info("Deleted volume: {}", volumeInfoDAO.getName());
  }
}
