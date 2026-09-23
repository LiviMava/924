CREATE TABLE kb_documents (
    id VARCHAR(36) PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    sha256 VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    index_mode VARCHAR(20) NOT NULL DEFAULT 'NONE',
    vector_collection VARCHAR(255),
    error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_documents_jobs ON kb_documents(status, updated_at);
CREATE TABLE kb_chunks (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL REFERENCES kb_documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    page_number INTEGER,
    section_title VARCHAR(500),
    UNIQUE(document_id, chunk_index)
);
CREATE INDEX idx_chunks_document ON kb_chunks(document_id);
