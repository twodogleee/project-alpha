package com._36knight.alpha.model;

import com._36knight.alpha.model.persistence.ModelPlatformEntity;
import com._36knight.alpha.model.persistence.ModelProfileEntity;
import com._36knight.alpha.model.persistence.ModelSelectionEntity;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理平台、模型及当前选择；写入锁住目录根，读取固定数据库快照。
 * 模型调用发生在事务外，密钥不进入公开目录。
 *
 * @author Andersen
 */
@Service
public class ModelCatalogService {
    /**
     * 已实现协议，平台名称和名单完全由数据库维护。
     */
    private static final Set<String> PROTOCOLS = Set.of("dashscope", "openai-compatible");
    /**
     * 目录访问与全局锁。
     */
    private final ModelCatalogRepository repository;
    /**
     * 沿用 V2 AES-GCM 主密钥和密文格式。
     */
    private final ModelSecretCipher cipher;

    /**
     * 注入存储和密钥边界。
     */
    public ModelCatalogService(ModelCatalogRepository repository, ModelSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /**
     * 多表公开读取使用同一快照，避免平台、模型和选择来自不同版本。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelCatalogView get() {
        var selection = selection();
        return new ModelCatalogView(selection.getRevision(),
            new ModelCatalogView.RuntimeSelection(selection.getMode(), selection.getActiveModelId()),
            repository.platforms().stream().map(p -> new ModelCatalogView.Platform(p.getId(), p.getName(), p.getProtocol(), p.getBaseUrl(), p.getModelCatalogUrl(), p.getUpdatedAt().toInstant())).toList(),
            repository.profiles().stream().map(p -> new ModelCatalogView.Model(p.getId(), p.getPlatformId(), p.getName(), p.getModel(), p.getApiKeyCiphertext() != null, p.getUpdatedAt().toInstant())).toList());
    }

    /**
     * 新增或更新平台；有已存密钥时禁止修改目标，避免凭证转交。
     */
    @Transactional
    public ModelCatalogView savePlatform(String id, SaveModelPlatformRequest request) {
        var selection = lock(request.expectedRevision());
        var item = id == null ? new ModelPlatformEntity() : platform(id);
        String name = name(request.name());
        if (!PROTOCOLS.contains(request.protocol())) throw ModelConfigurationException.invalid("请选择支持的接入协议");
        String url = normalizeUrl(request.baseUrl(), request.protocol());
        String catalogUrl = ModelCatalogUrl.normalize(request.modelCatalogUrl(), url);
        boolean targetChanged = id != null && (!item.getProtocol().equals(request.protocol()) || !item.getBaseUrl().equals(url));
        if (targetChanged && repository.profiles().stream().anyMatch(m -> m.getPlatformId().equals(id) && m.getApiKeyCiphertext() != null)) {
            throw ModelConfigurationException.invalid("修改平台协议或地址前，请先清除该平台下所有模型的 API Key；启用中的模型需先切换或停用");
        }
        if (id == null) item.setId(UUID.randomUUID().toString());
        item.setName(name);
        item.setProtocol(request.protocol());
        item.setBaseUrl(url);
        item.setUpdatedAt(now());
        item.setModelCatalogUrl(catalogUrl);
        unique(() -> {
            if (id == null) repository.insertPlatform(item);
            else repository.updatePlatform(item);
        });
        advance(selection);
        return get();
    }

    /**
     * 关联模型必须逐项处理，不级联删除模型或凭证。
     */
    @Transactional
    public ModelCatalogView deletePlatform(String id, long expectedRevision) {
        var selection = lock(expectedRevision);
        platform(id);
        if (repository.profiles().stream().anyMatch(p -> p.getPlatformId().equals(id))) {
            throw inUse("PLATFORM_IN_USE", "该平台仍有关联模型，请先移动或删除模型配置");
        }
        repository.deletePlatform(id);
        advance(selection);
        return get();
    }

    /**
     * 保存模型不自动启用；留空可保留同模型同平台的密文。
     */
    @Transactional
    public ModelCatalogView saveProfile(String id, SaveModelProfileRequest request) {
        var selection = lock(request.expectedRevision());
        var previous = id == null ? null : profile(id);
        platform(request.platformId());
        var next = buildProfile(previous, request);
        if (isActive(selection, id)) requireKey(next);
        unique(() -> {
            if (id == null) repository.insertProfile(next);
            else repository.updateProfile(next);
        });
        advance(selection);
        return get();
    }

    /**
     * 真实模式的启用模型不能删除；demo 删除已选项会同步清空选择。
     */
    @Transactional
    public ModelCatalogView deleteProfile(String id, long expectedRevision) {
        var selection = lock(expectedRevision);
        profile(id);
        if (isActive(selection, id)) throw inUse("MODEL_IN_USE", "该模型正在启用，请先切换到其他模型或本地联调模式");
        if (id.equals(selection.getActiveModelId())) {
            selection.setActiveModelId(null);
            repository.updateSelection(selection);
        }
        repository.deleteProfile(id);
        advance(selection);
        return get();
    }

    /**
     * 选择真实模型时检查凭证可解密，不发起平台请求。
     */
    @Transactional
    public ModelCatalogView select(SaveModelSelectionRequest request) {
        var selection = lock(request.expectedRevision());
        if (!Set.of("demo", "ai").contains(request.mode()))
            throw ModelConfigurationException.invalid("请选择有效运行模式");
        String id = request.activeModelId() == null || request.activeModelId().isBlank() ? null : request.activeModelId();
        if (id != null) {
            var selected = profile(id);
            platform(selected.getPlatformId());
            if ("ai".equals(request.mode())) requireKey(selected);
        }
        if ("ai".equals(request.mode()) && id == null)
            throw ModelConfigurationException.invalid("真实模式需要选择已配置 API Key 的模型");
        selection.setMode(request.mode());
        selection.setActiveModelId(id);
        advance(selection);
        return get();
    }

    /**
     * 单次运行读取并解密同一数据库快照，后续修改不影响本次客户端。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelConfigurationSettings activeSettings() {
        var selection = selection();
        if ("demo".equals(selection.getMode()))
            return new ModelConfigurationSettings("demo", "custom", "local-template", "", "");
        var model = profile(selection.getActiveModelId());
        return settings(model, platform(model.getPlatformId()), requireKey(model));
    }

    /**
     * 测试当前模型表单，不加密、不写表、不改变启用选择。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelConfigurationSettings testSettings(String id, SaveModelProfileRequest request) {
        if (selection().getRevision() != request.expectedRevision()) throw ModelConfigurationException.conflict();
        var previous = id == null ? null : profile(id);
        var target = platform(request.platformId());
        validateProfile(request);
        String key = inputKey(request.apiKey());
        if (key.isEmpty() && !request.clearApiKey() && previous != null && previous.getPlatformId().equals(request.platformId()))
            key = requireKey(previous);
        if (key.isEmpty())
            throw ModelConfigurationException.invalid("连接测试需要 API Key；移动平台后请填写对应的新密钥");
        return new ModelConfigurationSettings("ai", adapter(target), request.model().strip(), target.getBaseUrl(), key);
    }

    /**
     * 只读快照准备目录 GET 参数；凭证仅来自输入或当前模型同平台的密文，网络发生在事务外。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelCatalogFetchSettings catalogFetchSettings(String platformId, FetchPlatformModelsRequest request) {
        var selection = selection();
        if (selection.getRevision() != request.expectedRevision()) throw ModelConfigurationException.conflict();
        var target = platform(platformId);
        String url = ModelCatalogUrl.normalize(target.getModelCatalogUrl(), target.getBaseUrl());
        if (url == null) throw ModelConfigurationException.invalid("该平台尚未配置模型列表地址，请先编辑平台并保存");
        String key = inputKey(request.apiKey());
        if (request.clearApiKey() && !key.isEmpty())
            throw ModelConfigurationException.invalid("不能同时清除密钥和提交新密钥");
        var previous = request.modelId() == null || request.modelId().isBlank() ? null : profile(request.modelId());
        if (key.isEmpty() && !request.clearApiKey() && previous != null
            && previous.getPlatformId().equals(platformId) && previous.getApiKeyCiphertext() != null)
            key = requireKey(previous);
        return new ModelCatalogFetchSettings(URI.create(url), key, selection.getRevision());
    }

    /**
     * 构造模型持久化副本，移动平台必须新密钥或明确清除原凭证。
     */
    private ModelProfileEntity buildProfile(ModelProfileEntity previous, SaveModelProfileRequest request) {
        validateProfile(request);
        String key = inputKey(request.apiKey());
        boolean moving = previous != null && !previous.getPlatformId().equals(request.platformId());
        if (moving && previous.getApiKeyCiphertext() != null && key.isEmpty() && !request.clearApiKey()) {
            throw ModelConfigurationException.invalid("移动到其他平台时请填写新 API Key 或明确清除原密钥");
        }
        var next = new ModelProfileEntity();
        next.setId(previous == null ? UUID.randomUUID().toString() : previous.getId());
        next.setPlatformId(request.platformId());
        next.setName(name(request.name()));
        next.setModel(request.model().strip());
        next.setUpdatedAt(now());
        if (!key.isEmpty()) next.setApiKeyCiphertext(cipher.encrypt(key));
        else if (!request.clearApiKey() && !moving && previous != null)
            next.setApiKeyCiphertext(previous.getApiKeyCiphertext());
        return next;
    }

    /**
     * 模型标识与凭证命令必须有效，错误不回显输入。
     */
    private void validateProfile(SaveModelProfileRequest request) {
        name(request.name());
        if (request.model() == null || !request.model().strip().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,119}"))
            throw ModelConfigurationException.invalid("模型标识只能使用字母、数字、点、下划线、冒号、斜杠或短横线");
        if (request.clearApiKey() && request.apiKey() != null && !request.apiKey().isBlank())
            throw ModelConfigurationException.invalid("不能同时清除密钥和提交新密钥");
    }

    /**
     * 非空凭证禁止内部空白或控制字符，不在异常中携带原值。
     */
    private String inputKey(String input) {
        String key = input == null ? "" : input.strip();
        if (key.length() > 4096 || key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw ModelConfigurationException.invalid("API Key 格式不正确，请检查长度和空白字符");
        return key;
    }

    /**
     * 真实启用与测试必须有原主密钥可解密的凭证。
     */
    private String requireKey(ModelProfileEntity profile) {
        if (profile.getApiKeyCiphertext() == null) throw ModelConfigurationException.invalid("该模型尚未配置 API Key");
        String key = cipher.decrypt(profile.getApiKeyCiphertext());
        if (key.isBlank()) throw ModelConfigurationException.invalid("该模型 API Key 无效，请重新填写");
        return key;
    }

    /**
     * 生成调用的私有客户端参数。
     */
    private ModelConfigurationSettings settings(ModelProfileEntity model, ModelPlatformEntity platform, String key) {
        return new ModelConfigurationSettings("ai", adapter(platform), model.getModel(), platform.getBaseUrl(), key);
    }

    /**
     * 将数据库协议映射到现有 SDK 工厂，不依赖平台显示名称。
     */
    private String adapter(ModelPlatformEntity platform) {
        return "dashscope".equals(platform.getProtocol()) ? "dashscope" : "custom";
    }

    /**
     * 锁住根版本并拒绝多窗口陈旧请求。
     */
    private ModelSelectionEntity lock(long revision) {
        var selection = repository.lockSelection();
        if (selection == null) throw ModelConfigurationException.invalid("模型目录未初始化，请检查数据库迁移");
        if (selection.getRevision() != revision) throw ModelConfigurationException.conflict();
        return selection;
    }

    /**
     * 全部目录写入均递增同一个版本。
     */
    private void advance(ModelSelectionEntity selection) {
        selection.setRevision(selection.getRevision() + 1);
        selection.setUpdatedAt(now());
        repository.updateSelection(selection);
    }

    /**
     * 读取迁移创建的唯一选择，无环境默认回退。
     */
    private ModelSelectionEntity selection() {
        var selection = repository.selection();
        if (selection == null) throw ModelConfigurationException.invalid("模型目录未初始化，请检查数据库迁移");
        return selection;
    }

    /**
     * 找不到平台时使用明确业务状态。
     */
    private ModelPlatformEntity platform(String id) {
        var item = id == null ? null : repository.platform(id);
        if (item == null)
            throw new ModelConfigurationException(HttpStatus.NOT_FOUND, "PLATFORM_NOT_FOUND", "接入平台不存在，请重新载入目录");
        return item;
    }

    /**
     * 找不到模型时使用明确业务状态。
     */
    private ModelProfileEntity profile(String id) {
        var item = id == null ? null : repository.profile(id);
        if (item == null)
            throw new ModelConfigurationException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "模型配置不存在，请重新载入目录");
        return item;
    }

    /**
     * 当前真实运行选择不能被删除或清空凭证。
     */
    private boolean isActive(ModelSelectionEntity selection, String id) {
        return id != null && "ai".equals(selection.getMode()) && id.equals(selection.getActiveModelId());
    }

    /**
     * 名称允许中文，去除首尾空白，禁止控制字符。
     */
    private String name(String input) {
        if (input == null || input.isBlank() || input.strip().length() > 120 || input.chars().anyMatch(Character::isISOControl))
            throw ModelConfigurationException.invalid("名称不能为空、超过 120 字符或包含控制字符");
        return input.strip();
    }

    /**
     * 全局或平台内名称唯一，数据库竞争失败同样返回安全错误。
     */
    private void unique(Runnable operation) {
        try {
            operation.run();
        } catch (DuplicateKeyException exception) {
            throw ModelConfigurationException.invalid("名称已存在，请使用其他名称");
        }
    }

    /**
     * 引用关系阻止删除，前端保持原列表与编辑。
     */
    private ModelConfigurationException inUse(String code, String message) {
        return new ModelConfigurationException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 当前保存时间采用 UTC。
     */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * 远程限 HTTPS，本机回环可 HTTP；URL 不承载任何凭证。
     */
    private String normalizeUrl(String input, String protocol) {
        try {
            String value = input.strip().replaceAll("/+$", "");
            URI uri = URI.create(value);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || value.length() > 500
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback) || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            if ("dashscope".equals(protocol) && !uri.getPath().isEmpty())
                throw ModelConfigurationException.invalid("百炼协议请填写服务根地址，不包含 /api/v1");
            if (!"dashscope".equals(protocol) && (uri.getPath().isEmpty() || uri.getPath().endsWith("/chat/completions")))
                throw ModelConfigurationException.invalid("兼容协议请填写 API 基础路径（通常包含 /v1），不要包含 /chat/completions");
            return value;
        } catch (IllegalArgumentException exception) {
            throw ModelConfigurationException.invalid("接入地址需要有效 HTTPS URL，不包含账号、查询参数或片段；本机服务可用 HTTP");
        }
    }
}
