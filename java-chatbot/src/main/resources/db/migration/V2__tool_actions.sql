CREATE TABLE tool_actions (
    id VARCHAR(36) PRIMARY KEY,
    owner_hash VARCHAR(64) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    command_id VARCHAR(36) NOT NULL UNIQUE,
    arguments_json TEXT NOT NULL,
    preview_json TEXT NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_tool_actions_owner ON tool_actions(owner_hash, created_at);
